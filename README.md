# MonayCoinFlip

A Paper coinflip plugin with a browse GUI, player-head roulette animation, Vault economy wagers, formatted amounts, and opt-out notifications.

## Requirements
- Java 21
- Paper 1.21.4 (or compatible Paper build)
- [Vault](https://www.spigotmc.org/resources/vault.34315/)
- An economy plugin that provides Vault (for example EssentialsX)

## Build
```bash
mvn -U --batch-mode clean package
```
The plugin jar is generated at `target/MonayCoinFlip.jar`. GitHub Actions also builds the jar on pushes and pull requests; download it from the workflow's Artifacts section.

## Commands
- `/coinflip` — browse open coinflips
- `/coinflip add <amount>` — create a wager
- `/coinflip toggle` — toggle new-coinflip announcements
- Alias: `/cf`

Amount examples: `500`, `1k`, `1.5m`, `2b`, `100,000`, `$250`.

## How it works
The creator's wager is withdrawn when they list a flip. Another player must match the wager to join. Both players' heads appear in a roulette-style animation, with the center head/name changing while it spins. A random winner receives the combined pot (2x the wager). If a participant disconnects during the animation, both wagers are refunded. Unjoined wagers are refunded when the plugin disables.

## Configuration
Edit `plugins/MonayCoinFlip/config.yml`:
- `currency-symbol`
- `minimum-bet`
- `maximum-bet`

Notification preferences are saved automatically. This version keeps open coinflips in memory; open flips are refunded on plugin shutdown rather than persisted across restarts.
