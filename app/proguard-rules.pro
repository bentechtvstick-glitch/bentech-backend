# Kenbe tout kòd app la jan l ye (pa gen risk ak JSON / refleksyon); se sèlman bibliyotèk yo ki redwi
-keep class com.galaxytvstick.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**
