# iOS App Store Connect uploads

The mobile GitHub Actions workflow always builds an unsigned **release** IPA. A
published GitHub release attaches that unsigned IPA as a release asset. The
workflow separately signs the same IPA and uploads it to App Store Connect; the
signed IPA is not uploaded to GitHub as an artifact or release asset.

For a published GitHub release, the App Store Connect upload runs automatically.
For `workflow_dispatch`, select `upload_ios_to_app_store_connect` to request the
upload. Leave it unchecked to build only the unsigned IPA.

## Configure signing and App Store Connect credentials

Create these repository or organization Actions secrets before publishing a
release that should upload to Apple:

| Secret                                      | Value                                                                                   |
| ------------------------------------------- | --------------------------------------------------------------------------------------- |
| `IOS_DISTRIBUTION_CERTIFICATE_BASE64`       | Base64-encoded `.p12` containing an **Apple Distribution** certificate and private key  |
| `IOS_DISTRIBUTION_CERTIFICATE_PASSWORD`     | Password used to export that `.p12`                                                     |
| `IOS_APP_STORE_PROVISIONING_PROFILE_BASE64` | Base64-encoded App Store distribution provisioning profile for `com.sidpatchy.Starling.524H4S3B9K` |
| `APPLE_TEAM_ID`                             | Apple Developer Team ID that owns the certificate and profile                           |
| `ASC_KEY_ID`                                | App Store Connect API key ID                                                            |
| `ASC_ISSUER_ID`                             | App Store Connect API issuer ID                                                         |
| `ASC_PRIVATE_KEY`                           | Full contents of the API key's `.p8` private key, including the PEM header and footer   |

The App Store Connect API key needs permission to upload builds for this app
(an **App Manager** key is suitable). The signing certificate, profile, and API
key must belong to the same Apple team. The profile must use the exact Starling
bundle ID and App Store distribution, not development or ad hoc distribution.

To create the base64 values without line breaks on macOS:

```sh
base64 -i AppleDistribution.p12 | tr -d '\n'
base64 -i Starling-AppStore.mobileprovision | tr -d '\n'
```

Paste each output into the corresponding GitHub secret. Store the `.p8` file's
raw multiline contents in `ASC_PRIVATE_KEY`; do not commit any of these files or
values to the repository.

The iOS bundle ID is `com.sidpatchy.Starling.524H4S3B9K`. The workflow uses the numeric release version for `CFBundleShortVersionString`
and the GitHub Actions run number for `CFBundleVersion`. If Apple has already
accepted an upload for a run, start a new workflow run for another upload so it
gets a new build number. Uploading submits the build for Apple's processing; it
does not automatically release the app to users.
