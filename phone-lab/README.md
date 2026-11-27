# Elsewhere Phone Lab
An intentionally offline interaction prototype, not a second copy of the full RP engine. Includes synthetic Hero Society and Afterglow examples. Roleplay, API keys, import and true persistent lore or memory are not wired. Demo state is in-memory for the running Activity.
Build: ./gradlew :phone-lab:assembleDebug
Package: com.aistudio.elsewhere.abwzy.phonelab; no shared UID, INTERNET permission, or backup access. Uses Gradle's ordinary debug signer. Unless built again with the same debug key, a future APK may require reinstalling the Lab only.
