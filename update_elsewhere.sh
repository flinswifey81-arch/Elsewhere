#!/bin/bash
sed -i 's/appContainer = container,/chatRepository = container.chatRepository,/g' app/src/main/java/com/example/ui/navigation/ElsewhereApp.kt

# Now for ChatDetailScreen, specifically replace chatRepository with appContainer
awk '
/ChatDetailScreen/ { in_chat_detail=1 }
in_chat_detail && /chatRepository = container.chatRepository/ {
    sub(/chatRepository = container.chatRepository/, "appContainer = container")
    in_chat_detail=0
}
{ print }
' app/src/main/java/com/example/ui/navigation/ElsewhereApp.kt > temp.kt
mv temp.kt app/src/main/java/com/example/ui/navigation/ElsewhereApp.kt
