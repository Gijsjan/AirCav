# The native library resolves JNI symbols by exact class/method name
# (Java_dev_ruimdetijd_aircav_AirCavLib_*); R8 must not rename or remove them.
-keep class dev.ruimdetijd.aircav.AirCavLib { *; }
