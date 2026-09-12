#!/bin/bash
sed -i 's/providerName/provider/g' app/src/main/java/com/example/ui/screens/ChatDetailScreen.kt
sed -i 's/durationMs/generationTimeMs/g' app/src/main/java/com/example/ui/screens/ChatDetailScreen.kt
sed -i 's/updatedAt/editedAt/g' app/src/main/java/com/example/ui/screens/ChatDetailScreen.kt
sed -i 's/updatedAt/editedAt/g' app/src/main/java/com/example/ui/screens/ChatDetailViewModel.kt
