// TdApi.java + Client.java + libtdjni.so from the official TDLib Android build (see README).
// Own module so the 10 MB TdApi.java isn't recompiled with every app change.
plugins { id("com.android.library") }

android {
    namespace = "org.drinkless.tdlib"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig { minSdk = 29 }
}

dependencies { implementation("androidx.annotation:annotation:1.11.0") }
