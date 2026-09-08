# Handoff: JOSM Lanelet2 plugin

## TODO: verify GraalPy ↔ lanelet2 import on JDK 25

Classloader injection is in place on both sides (`ScriptingVisibility` →
`graalpy`, `PluginVisibility` ← every other plugin). A headless Java test
already loads `Lanelet2Extensions` through an injected engine loader and
calls `settings()` / `anchors()`.

The **Python 3** import was not run. This machine only has JDK 21; GraalPy’s
suite needs a **JDK 25 toolchain** (JEP 454 / NumPy). On a JDK 25 box:

```bash
# after JOSM_LL2_Plugin/build/dist/lanelet2.jar exists
cd /ll2_tooling_root/JOSM_LL2_Plugin && ./gradlew dist

cd /ll2_tooling_root/JOSM_GraalPy_Plugin
./gradlew test --tests '*.pythonImportsLanelet2AfterInjection'
```

Expect:

```python
from org.openstreetmap.josm.plugins.lanelet2.api import Lanelet2Extensions
Lanelet2Extensions.settings().get("lanelet.default_subtype", "road")  # "road"
```

Then, with **both** plugins loaded in one JOSM (`runJosm` in the GraalPy
repo does not load `lanelet2.jar` by itself):

1. Open a data layer.
2. *Tools → Run Python file…* → `JOSM_LL2_Plugin/examples/graalpy/hello_lanelet2.py`.

Stock OpenJDK 25 is enough. Do not install GraalVM JDK 21 for this.
