# Native entry points are resolved through Java_com_phytoy_engine_... symbols.
# Keep their owning class and names while allowing ordinary Kotlin API optimization.
-keep,allowoptimization class com.phytoy.engine.PhyToyCameraSession {
    private static native <methods>;
}
