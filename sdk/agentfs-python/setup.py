from setuptools import Distribution, setup


class NativeDistribution(Distribution):
    def has_ext_modules(self):
        return True


setup(
    name="agentfs",
    version="0.1.0",
    description="Standalone AgentFS client for JuiceFS volumes",
    packages=["agentfs"],
    package_data={"agentfs": ["libjfs.so"]},
    distclass=NativeDistribution,
)
