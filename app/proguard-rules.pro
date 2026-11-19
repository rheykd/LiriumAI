# JNI entry points are referenced from Kotlin; keep them.
-keep class com.localmodels.app.engine.LlamaBridge { *; }
-keep class com.localmodels.app.engine.TokenSink { *; }
-keep class com.localmodels.app.data.CatalogModel { *; }
