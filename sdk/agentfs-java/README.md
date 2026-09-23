# 独立 Java AgentFS SDK

> **读者**：Java 应用开发者。**前置**：JDK 17、已有 JuiceFS 卷及可信连接配置。**读完能**：本地安装 SDK，调用文件工具并处理分页和编辑错误。**visibility**: public

普通 Java 应用可直接读写 JuiceFS、列目录、精确搜索和编辑单个文件。运行时不需要 Hadoop、JuiceFS 命令行客户端或文件系统挂载。

本轮本地开发验收已完成，包含独立代码/API、安全和测试完整性审查；不代表生产发布或全平台支持。边界见文末“验证状态”。

SDK 通过 JNR 调用打包的 `libjfs` 本机动态库；`libjfs` 负责元数据与对象存储访问。`org.json` 用于生成初始化配置。它们及传递依赖仍有体积和内存成本，SDK 不是纯 Java 实现。

| 任务 | 入口 |
| --- | --- |
| 安装并连接卷 | [本地安装](#本地安装)、[初始化与调用](#初始化与调用) |
| 分页、搜索、扩展搜索实现 | [六个文件操作](#六个文件操作)、[搜索实现](#搜索实现) |
| 编辑及 Agent 集成 | [编辑与错误处理](#编辑与错误处理)、[封装为 Agent 工具](#封装为-agent-工具) |

## 本地安装

已验证平台为 Linux arm64、JDK 17。构建脚本也接受 Linux amd64，但该平台未验证。没有 macOS/Windows 打包支持声明；缺少匹配本机库时初始化报错。

在目标 Linux 平台安装 Go 1.25.10、C 编译器、Maven、JDK 17 和支持 PCRE2 的 ripgrep。Go/C 用于构建本机库，ripgrep 用于搜索实现测试。应用使用默认 Raw 搜索时不需要这些构建工具、bash 或 rg。

从仓库根目录执行本地安装：

```sh
bash sdk/agentfs-java/scripts/build-native.sh
mvn -B -f sdk/agentfs-java/pom.xml install
```

原生资源先生成到 `target/native-resources`，Maven 再将其打入 JAR；不要在两步之间执行 `mvn clean`。产物只安装到本地 Maven 仓库，没有发布到中央仓库。

消费应用加入依赖：

```xml
<dependency>
  <groupId>io.juicefs</groupId>
  <artifactId>agentfs-java</artifactId>
  <version>0.1-SNAPSHOT</version>
</dependency>
```

[consumer/](consumer/) 是只声明该依赖的普通 Maven 应用。`NativeConsumer` 演示六个操作、自定义搜索实现及客户端关闭；其中超级用户和拒绝访问文件用于隔离测试，不是业务应用的默认身份。应用仍需能连接卷使用的元数据与对象存储服务。

## 初始化与调用

身份必须由可信应用配置提供。SDK 不认证任意用户名，不从模型参数接收身份或元数据地址，也不接入 Hadoop/Ranger/Kerberos 授权。需要这些授权体系的应用继续使用原 SDK。

```java
import io.juicefs.agentfs.AgentFS;
import java.nio.charset.StandardCharsets;
import java.util.List;

// Load these values from trusted application configuration.
String volume = System.getenv("AGENTFS_VOLUME");
String metadataUrl = System.getenv("AGENTFS_META");
String user = System.getenv("AGENTFS_USER");
String group = System.getenv("AGENTFS_GROUP");

try (AgentFS fs = AgentFS.builder(volume, metadataUrl)
        .identity(user, List.of(group))
        .open()) {
    fs.writeFile("/notes/demo.txt", "cat\n".getBytes(StandardCharsets.UTF_8));
    AgentFS.ReadResult read = fs.readFile("/notes/demo.txt", 0, 4096);
    AgentFS.DirectoryPage files = fs.listDirectory("/notes", 0, 20);
    AgentFS.SearchPage hits = fs.search("cat", "/notes", 20, null);
    fs.editFile("/notes/demo.txt", "cat", "dog");
    fs.applyPatch("/notes/demo.txt",
            "*** Begin Patch\n*** Update File: /notes/demo.txt\n@@\n"
            + "-dog\n+fox\n*** End Patch\n");
}
```

默认超级用户名为 `hdfs`，超级组为 `supergroup`；这不是默认运行身份。可信应用可用 `.superuser(name, group)` 配置对应名称，权限不足会报错，不自动提权。原生组映射仍生效；同一卷名下同一用户名的新初始化可更新已有同名客户端的组，不应把它们当成相互隔离的不同主体。

客户端由应用持有并关闭。先结束操作、关闭搜索实现打开的流，再调用 `close()`；可用 try-with-resources。重复关闭允许，关闭后操作报 `IOException`；关闭一个客户端不会使另一个仍打开的客户端失效。不要让关闭与正在执行的操作并发。

## 六个文件操作

所有路径使用卷内绝对路径，如 `/notes/demo.txt`，不使用 `jfs://` URI。路径不能为空、不能包含 NUL，必须能编码为 UTF-8；目录分页不提供跨调用快照。

| 方法 | 结果与边界 |
| --- | --- |
| `readFile(path, offset, limit)` | 按字节读取；返回 `data`、`offset`、`nextOffset`、`truncated`。`offset >= 0`，`limit > 0` 且小于 `Integer.MAX_VALUE`。截断时从 `nextOffset` 续读。 |
| `writeFile(path, byte[])` | 创建缺失父目录，替换已有普通文件；返回 `path`、`bytesWritten`。不是保留属性的编辑或事务写，写入失败不承诺恢复旧内容。 |
| `listDirectory(path, offset, limit)` | 按 UTF-8 路径字节排序；返回条目的 `name/size/type` 及 `truncated/nextOffset`。只限制返回页大小，底层先枚举目录。 |
| `search(query, root, limit, cursor)` | 默认递归搜索目录 `root`；返回 `matches`、`nextCursor`、`complete`、`skipped`。匹配项包含 `path/offset/length`，位置与长度均为字节。 |
| `editFile(path, oldText, newText)` | UTF-8 精确替换，旧文本非空且必须唯一匹配；空新文本表示删除。返回 `path/replacements/bytesWritten`。 |
| `applyPatch(path, patch)` | 单文件上下文补丁；结果字段同 `editFile`。不支持行号补丁、多文件或 Add/Delete/Move。 |

搜索区分大小写，按查询字符串的 UTF-8 字节匹配，包含重叠匹配；不分词、不做 Unicode 归一化或语义搜索。Raw/Ripgrep 的 `root` 必须是目录，传文件路径不等同于单文件搜索。自定义实现自行履行搜索契约。

逐页沿用同一个查询、根目录和搜索实现，把 `nextCursor` 原样传回，直到它为 `null`。`complete=true` 要求已到最后一页且本次没有已知跳过项。检查并累计每页 `skipped`：权限失败、文件变化等缺口不能被解释为“没有匹配”。空结果或没有续页都不单独证明完整搜索；文件变化检测不提供一致性快照。

## 搜索实现

默认 `RawSearchProvider` 在 SDK 内扫描文件，不启动外部进程。选择 Ripgrep 需要运行环境提供支持 PCRE2 的 `rg`：

```java
import io.juicefs.agentfs.RipgrepSearchProvider;

AgentFS fs = AgentFS.builder(volume, metadataUrl)
        .identity(user, List.of(group))
        .searchProvider(new RipgrepSearchProvider("rg"))
        .open();
```

可把 `"rg"` 换成可信可执行文件的绝对路径。SDK 直接启动 rg，不经 shell；缺失 rg 或进程失败抛出 `SearchProviderException`，不会自动切回 Raw。该错误与 `skipped` 所表示的文件访问缺口不同。

自定义搜索实现 `SearchProvider.search(SearchFiles, query, root, limit, cursor)` 并通过 `.searchProvider(provider)` 注入。`SearchFiles` 只提供 `stat`、`list`、`open`，不暴露 Hadoop 类型；实现者负责关闭返回的流。可参考 [NativeConsumer.java](consumer/src/main/java/NativeConsumer.java) 的自定义实现。Raw/Ripgrep 的续页不能跨查询、根目录或实现混用。

## 编辑与错误处理

编辑只接受已有普通文件，且硬链接数为 1、没有扩展 ACL/xattr；拒绝末级符号链接。调用者需要目标文件读写权限和父目录创建/替换权限，sticky 目录仍受属主限制。成功编辑保留数字 uid/gid 和权限位；不保留 inode、时间戳或稀疏布局。无变化返回 `replacements=0, bytesWritten=0`，不替换文件；有变化的 `bytesWritten` 是整个输出长度。

补丁使用 `*** Begin Patch`、唯一 `*** Update File: <path>` 和 `*** End Patch`。`@@` 可带唯一的整行锚点；空格、`-`、`+` 分别表示上下文、删除和新增。所有块必须在原文件中唯一、按序且不重叠，验证完成后才提交。`*** End of File` 把最后一块定位到文件末尾，LF/CRLF 字节有意义。

新 SDK 保留未修改上下文的换行：从无末尾换行的 `cat\ndog` 只删除 `dog`，结果是 `cat\n`。独立测试保留共享 50 个输入，只将 `eof_delete_none` 的期望从 Python 的 `cat` 调整为 `cat\n`。旧 Java/Python SDK 不再包含本分支新增的 AgentFS 编辑入口；共享样例不变。

普通参数错误使用 `IllegalArgumentException`，原生操作失败使用 `IOException`。编辑的 `EditException` 继承 `IOException`，有 `code`、`outcome`、`stage`；错误码包括 `invalid_input`、`invalid_patch`、`no_match`、`ambiguous_match`、`unsupported`、`storage_error`、`commit_unknown`。

`outcome=unchanged` 表示本次调用未提交，不排除其他写入者已改文件。`code=commit_unknown`、`outcome=unknown` 表示替换可能已生效；读取文件并核对后再决定下一步，**不要自动重试或自动回滚**。保留异常及其 suppressed exceptions，清理失败的临时路径会附在其中。

调用方必须串行化同一文件的完整“读内容、生成修改、提交”过程，包含其他写入者。SDK 不提供锁、多文件事务或崩溃后自动清理。

## 封装为 Agent 工具

应用在模型之外初始化客户端，只注册文件操作。下面是应用自有包装类；模型只提供路径及读取范围，不提供身份和连接配置：

```java
final class FileTools {
    private final AgentFS fs;

    FileTools(AgentFS fs) {
        this.fs = fs;
    }

    public AgentFS.ReadResult read(String path, long offset, int limit)
            throws java.io.IOException {
        return fs.readFile(path, offset, limit);
    }

    public AgentFS.EditResult replace(String path, String oldText, String newText)
            throws java.io.IOException {
        return fs.editFile(path, oldText, newText);
    }
}
```

由应用的工具框架注册这些方法并序列化结果；字节结果按框架需要转换为 Base64 或在确认编码后转文本。应用负责工具调用授权和同文件串行调度。关闭客户端的职责仍在应用，不将 `close` 或初始化方法注册为模型工具。

## 运行验证

在能访问已有隔离测试卷的 Linux 构建环境中运行。除上述 Go/C、Maven/JDK 17 外，还需 Python 3、`six` 和支持 PCRE2 的 rg。通过可信环境配置提供 `AGENTFS_META` 和 `AGENTFS_VOLUME`，不要使用业务卷。

从仓库根目录执行：

```sh
bash sdk/agentfs-java/scripts/verify.sh all
# Run only the edit integration route, including Python/Java exchange.
bash sdk/agentfs-java/scripts/verify.sh edit
```

路由为 `all`（默认）、`native`、`files`、`search`、`edit`、`regression`、`cost`。每条路由都会先构建和安装 SDK、准备唯一路径、验证独立消费者，再运行对应场景。脚本不格式化已有卷；旧 SDK 回归只格式化新建临时目录内的 SQLite 测试卷。测试数据保留，不自动清理已有卷数据。

## 验证状态

- Linux arm64/JDK 17 已验证原生权限、55 个新 SDK 单元/集成测试、独立打包消费者的六个操作和自定义实现，以及 Python/Java UTF-8 读写、搜索与编辑互操作。默认 Raw 消费者可在没有 bash/rg 的运行 PATH 中工作。
- 当前 `verify.sh all` 已通过，包含旧 Java 定向状态/权限回归、旧 Python Client 冒烟检查及审计脚本 19 项测试。旧 Python/Java SDK 已移除本分支加入的 AgentFS 入口；独立 SDK 的运行时不依赖它们。这不表示整个旧 SDK 测试库或全部平台都已验证，尚无新模块的百分比覆盖率基线。
- 原生 stat 的属主名与组名合计最多容纳 100 个 UTF-8 字节（不是字符数）。恰好 100 字节保留完整名称；超过时查询或目录列举返回容量错误 `EOVERFLOW`（编号 75），不截断身份。编辑在提交前失败且原文件不变；搜索会报告遍历缺口，不冒称完整结果。初始化本身不限制名字长度，不能将初始化成功等同于属性查询一定成功。
- 旧 Hadoop SDK 的 `newFileStatus` 已修正为按 UTF-8 字节偏移解析属主/组名，查询和目录列举边界回归通过；公共接口不变。隔离旧 SDK 回归同时显式设置源码和 JVM 编码为 UTF-8，保留原断言。
- libjfs 完整测试的 `TestPush` 和完整 `go vet` 的指针转换告警在未修改身份逻辑的基线也存在，作为已知限制保留。可用 `go test -tags nogspt ./sdk/java/libjfs -count=1` 和 `go vet -tags nogspt ./sdk/java/libjfs` 复验；不能把专项通过描述为全包检查通过。
- Linux amd64 未验证；需在该平台重新构建并运行原生与打包消费测试。
- 同平台实测运行时 JAR 数从旧 Hadoop 接入的 93 个降到 12 个，均包含 SDK 本身。首次访问中位耗时未变快；本机库仍占主要体积。[成本对比](cost/README.md) 提供体积、耗时、RSS 原始读数和复验命令，不代表生产吞吐或通用性能保证。
