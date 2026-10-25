# BabiTrans R8 rules (pattern from BabiSnap). JNI: native methods are bound by name (Java_com_erezbabayan_babitrans_Whisper_*).
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.erezbabayan.babitrans.Whisper { *; }
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
