# Keep string R fields visible for automatic ad unit discovery in release builds.
-keep class **.R$string {
    public static int *;
}
