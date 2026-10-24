# Elsewhere testing APK

The `sandbox` build type creates a separately installable test application:

- Application ID: `com.aistudio.elsewhere.abwzy.test`
- Launcher label: `Elsewhere Test`
- Gradle task: `:app:assembleSandbox`
- APK: `app/build/outputs/apk/sandbox/app-sandbox.apk`

Android treats this package as a separate application, with its own UID, Room database, files,
preferences, cache, and AndroidKeyStore entries. Installing or clearing it does not modify the
production Elsewhere application's private storage.

## Stable GitHub Actions signing

The manual `Build Android Testing APK` workflow requires a dedicated testing keystore stored in
GitHub Actions secrets. Create and retain this keystore outside the repository, then configure:

- `ELSEWHERE_TEST_KEYSTORE_BASE64`: base64 encoding of the complete keystore file
- `ELSEWHERE_TEST_KEYSTORE_PASSWORD`: keystore password
- `ELSEWHERE_TEST_KEY_ALIAS`: signing-key alias
- `ELSEWHERE_TEST_KEY_PASSWORD`: signing-key password

Never commit the keystore or passwords. Keep an offline recovery copy. Every future testing APK
must use the same key to update an installed `Elsewhere Test` app without removing its data. The
workflow disables Gradle's configuration cache for the signed build and only restores the key in
the temporary GitHub runner directory. The workflow removes that temporary key immediately after
the build.

After the workflow exists on the default branch, open **Actions**, choose **Build Android Testing
APK**, select **Run workflow**, and choose `feature/narrator-npc-turns` from the branch list. The
checkout step uses the selected workflow-dispatch ref, and the uploaded artifact is named
`Elsewhere-Test-APK`.

## Backup compatibility

Production Elsewhere accepts backups whose manifest contains only the production application ID.
Elsewhere Test accepts both its own application ID and the production application ID, allowing a
production encrypted backup to seed the isolated test installation. This is intentionally one-way:
production rejects backups exported by Elsewhere Test. Encryption, passphrase verification,
checksums, format versions, database versions, file validation, and pre-restore safety snapshots
remain unchanged.

Portable backups do not contain the OpenRouter API key. Configure that separately in Elsewhere Test
if provider testing is needed.
