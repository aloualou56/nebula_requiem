# Nebula Requiem uses no reflection, serialization frameworks or JNI, so R8 needs no keep rules
# beyond the defaults. Keep line numbers for readable crash traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
