CH350 emoticons = Java/Kotlin (in this repo) + native lib + .obb packs

What is NOT in the CH350 source tree
--------------------------------------
The 350 SMS Git checkout typically does NOT contain:
  - libresutils.so  (JNI; referenced only by scripts such as app/1_push SO files.bat)
  - *.obb emoticon packs (loaded at runtime from disk)

Those come from the same OEM/build pipeline that produced your CH350 APK.

libresutils.so (required for the grid to work)
-----------------------------------------------
1) Build CH350_SMS on a machine that produces jniLibs, OR extract from an installed CH350 APK:
     unzip -p YourCh350.apk "lib/arm64-v8a/libresutils.so" > arm64-v8a/libresutils.so
     unzip -p YourCh350.apk "lib/armeabi-v7a/libresutils.so" > armeabi-v7a/libresutils.so
2) Place files here:
     app/src/main/jniLibs/arm64-v8a/libresutils.so
     app/src/main/jniLibs/armeabi-v7a/libresutils.so
   (add x86/x86_64 only if you ship those ABIs.)

.obb packs
----------
Either:
  A) Put *.obb in app/src/main/assets/ch350_emoji/  (bundled; copied to app files on first use), or
  B) Push/copy *.obb into the app’s files directory:
         <filesDir>/ch350_emoji_obb/
     or external:
         <getExternalFilesDir>/ch350_emoji_obb/
  C) On firmware that still has it, the app also scans CH350’s OEM path: /product/txDCS/

Without BOTH libresutils and at least one valid .obb, initResourceDb fails and the UI shows a short hint.
