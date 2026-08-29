# Keep AIDL-generated parcelable/stub classes across app boundary
-keep class com.arkj.llm.contract.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
