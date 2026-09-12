#!/bin/bash
sed -i 's/assertTrue(messages\[0\].content.contains("MinimalChar"))/println(messages[0].content)\n        assertTrue(messages[0].content.contains("MinimalChar"))/g' app/src/test/java/com/example/domain/Stage3SoloRoleplayTests.kt
