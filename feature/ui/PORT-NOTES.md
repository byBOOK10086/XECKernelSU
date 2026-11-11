# feature/ui - port notes and exclusions

Decisions taken while porting the manager UI from `XECKernelPro@v31424` (the
"cleanest environment" release) onto the upstream base. Written down here so the
next person does not have to rediscover them.

## Source rule

Most files come from `v31424`; the motion token table (`XcMotion.kt`) and the
dock (`Xc8Dock.kt`) come from the later revision, because that rewrite is what
removed the black frame and the ghost of the previous page on device.

For any file that **exists upstream**, the rule is "diff, do not overwrite":
upstream screens keep calling upstream component APIs, so replacing a shared
component with our variant breaks call sites elsewhere in the tree. Measured
while porting the component layer: 55 files staged, 37 of them overwrote an
upstream file, and those 37 immediately produced 12 compile errors in six
upstream screens.

Only files upstream does not have are added verbatim.

## Not carried over

| Excluded | Why |
|---|---|
| `ui/screen/detect/**` | the built-in detection screen is cancelled |
| `mountstate/**`, `MagiskRoot`, `MagiskSuperUser*`, `HomeModeCard`, `RootRoute` | the Magisk mount-state route is gone; supported kernels start at 5.10 |
| `settings/TeePanel`, `TeeDiagnostics`, `TeeAttestation`, `TeeStatusSection`, `RkpRepair`, `TarScan`, `KeyDescription`, `PifConfig`, `PifSection` | the TEE repair/diagnostic chain is gone; TEE is an ordinary module plus `feature/tee` |
| `kernel/apatch/**` (never enters this repo) | APatch dialect dropped: it was a second root authority next to the KernelSU allowlist |
| XUDC layer (`xecinit`, `xecsu`, `su_daemon`, `ramdisk_patch`, `magic_mount`, `root_mode`) | upstream `ksuinit` + `ksud` already cover it |
| 46 of 47 locales | only `zh-rCN` and `en` are maintained |
| `ui/component/bottombar/BottomBarMiuix.kt` | ours had five tabs including the detection page; upstream's four-tab bar is kept |
| `ui/screen/home/XecScore.kt`, `ui/screen/tools/XecScoreCard.kt`, `widget/XecScoreWidgetProvider.kt`, `ui/util/SELinuxChecker.kt` | the score surface is the detection view by another name - confirmed dropped as well: no score card on the home screen, none in the tools page, no widget, no checker |
| `ui/screen/hidepack/**` | one-tap hide cancelled; the package's other two files (the download list and the environment list) only exist to serve it |
| `ui/screen/tools/ToolsScreen.kt` | a navigation hub whose entries are almost all features that are not coming (detection score, TEE diagnostics, PIF, stealth, privilege). It gets rewritten once the navigation shell exists, instead of being ported against routes that do not |
| `ui/screen/modulerepo/**` (our version) | measured, then reverted: our registry state is an older, smaller model than upstream's. Replacing it deleted `installedVersions`, `stale`, `RepoInstallState`, `repoInstallStateOf`, the detail/script flags and the star formatting, which broke 154 call sites in upstream's page. The registry is upstream's feature and its version is the better one, so this page stays upstream |

## Layers, in the order they landed

1. `ui/design/**` - tokens, panels, glass, clear, island, kit (5,733 lines).
   Self-contained: no JNI, no ksud command, no view model.
2. `data/theme/**` - codec, repository, resolver, store, catalog, applier
   (2,483 lines) plus `SettingsRepository` wallpaper preferences and the shared
   `sha256Of` / `downloadToFile` / `hasInternetTransport` helpers. The theme store
   used to reach into `ui/screen/remote/RemoteDownloader`; that dependency was
   removed and the transfer moved to `ui/util`.
3. `ui/component/**` - only the five files upstream lacks (`XDialog`,
   `XDialogHost`, `RemotePreview`, `PagerNavigationSpring`, `XcGlassKernel`).
   They land ahead of their first consumer, so they compile standalone.

## Gate for every batch

`:app:compileReleaseKotlin` green against the upstream base, then a blob-level
byte check of everything pushed.
