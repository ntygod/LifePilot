---
name: weather
description: "Get current weather and forecasts via wttr.in or Open-Meteo."
---

# Weather Skill

## When to Use
- "What's the weather?"
- "Will it rain today/tomorrow?"
- "Temperature in [city]"

## Commands

### Current Weather
```bash
curl "wttr.in/London?format=3"
curl "wttr.in/London?0"
```

### Forecasts
```bash
curl "wttr.in/London"          # 3-day
curl "wttr.in/London?format=v2" # week
curl "wttr.in/London?1"         # tomorrow
```

### Format Codes
- %c — condition emoji, %t — temperature, %f — feels like
- %w — wind, %h — humidity, %p — precipitation

## Notes
- No API key needed (uses wttr.in)
- Rate limited; don't spam
