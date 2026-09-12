#!/bin/bash
sed -i 's/onNavigateToSettings = { \/\* TODO settings \*\/ }/onNavigateToSettings = { navController.navigate("chat_settings\/$chatId") }/g' app/src/main/java/com/example/ui/navigation/ElsewhereApp.kt

# Insert the route
awk '
/composable\("context_inspector\/{chatId}"\)/ { 
    print "            composable(\"chat_settings/{chatId}\") {"
    print "                val chatId = it.arguments?.getString(\"chatId\") ?: \"\""
    print "                ChatSettingsScreen("
    print "                    chatId = chatId,"
    print "                    appContainer = container,"
    print "                    onNavigateBack = { navController.popBackStack() }"
    print "                )"
    print "            }"
}
{ print }
' app/src/main/java/com/example/ui/navigation/ElsewhereApp.kt > temp.kt
mv temp.kt app/src/main/java/com/example/ui/navigation/ElsewhereApp.kt
