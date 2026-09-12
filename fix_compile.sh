#!/bin/bash
sed -i '/messages.add(RoleplayMessage(role = role, content = currentMessage.content, name = currentMessage.speakerDisplayNameSnapshot))/a \
        \n        if (isContinuation) {\n            messages.add(RoleplayMessage(role = Role.SYSTEM, content = "Continue the previous message. Do not repeat what you have already said."))\n        }' app/src/main/java/com/example/domain/compiler/ContextCompilerV1.kt
