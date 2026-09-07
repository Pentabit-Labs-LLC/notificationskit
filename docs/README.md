# docs/

Served by GitHub Pages (repo Settings → Pages → Source: `main` branch, `/docs` folder) as a plain
public, unauthenticated Maven repository for NotificationsKit's "common" multiplatform artifact.

`maven-repo/` is written to by `notifications-kit`'s release process
(`./gradlew :notificationskit:publish` → copy `build/maven-repo/` here → commit → push) — see that
repo's README, "Releasing the common (multiplatform) artifact". Don't edit anything under here by
hand; don't delete an already-published version's folder, something may depend on it.
