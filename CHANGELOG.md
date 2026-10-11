# Changelog

## 1.10.10

- Added a simple join experience by default: regular players see one clickable leaderboard entry; operators retain their administration controls, and help documents the mode switch.
- Fixed scoreboard refresh flicker by updating only changed rows instead of recreating the whole display.
- Extended flight-distance tracking beyond the integer limit, standardized kilometer display, and added an operator command for editing leaderboard values during testing.
- Consolidated Fabric and NeoForge builds into one direct JAR per supported family (`1.21.x` and `26.x`) and added non-publishing CI builds for pushes and pull requests.

## 1.10.6

- Fixed cross-patch Minecraft API linkage for the single 1.21.x Fabric and NeoForge JARs, including player profile/avatar data, persistent state, NBT, component lookup, command permissions, registry identifiers, and statistics.
- Simplified the join leaderboard prompt for regular players to one clickable “Expand leaderboard info” button. Operators keep the detailed join menu and its whitelist, scoreboard cleanup, language, and administration controls.
- Updated the all-version compatibility audit and added isolated server/player-join smoke checks for supported version families.
