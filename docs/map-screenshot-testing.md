# Map screenshot testing

## Quick Start

```js
const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({
    headless: true,
    args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-webgl', '--ignore-gpu-blocklist'],
  });
  const page = await browser.newPage();

  // Inject auth token (user ID 1, JWT from .env JWT_SECRET)
  await page.addInitScript(token => {
    localStorage.setItem('tracks_token', token);
  }, 'YOUR_JWT_TOKEN_HERE');

  // Navigate to map page
  await page.goto('http://localhost:4080/maps', { waitUntil: 'networkidle', timeout: 30000 });
  await page.waitForTimeout(12000); // Wait for tiles to load

  // Fly to target location and zoom
  await page.evaluate(() => {
    const map = window.__mapDebug;  // Set by useMapInit.js during debug
    if (map) map.flyTo({ center: [6.87, 45.92], zoom: 7.5, duration: 0 });
  });
  await page.waitForTimeout(7000);

  // Take screenshot
  await page.screenshot({ path: '/tmp/map-screenshot.png' });

  // Check tile status
  const tileInfo = await page.evaluate(() => {
    const map = window.__mapDebug;
    if (!map) return {};
    const sc = map.style.sourceCaches;
    const out = {};
    for (const [id, cache] of Object.entries(sc)) {
      let loaded = 0, errored = 0;
      for (const [, tile] of Object.entries(cache._tiles || {})) {
        if (tile.state === 'loaded') loaded++;
        if (tile.state === 'errored') errored++;
      }
      if (loaded || errored) out[id] = `${loaded}L ${errored}E`;
    }
    return { zoom: map.getZoom(), center: map.getCenter(), tiles: out };
  });
  console.log(JSON.stringify(tileInfo, null, 2));

  await browser.close();
})();
```

## Prerequisites

1. **JWT token**: Generate one or get from `.env` JWT_SECRET. Create with:
   ```python
   from jose import jwt
   from datetime import datetime, timedelta, timezone
   token = jwt.encode(
       {'sub': '1', 'exp': datetime.now(timezone.utc) + timedelta(days=30)},
       'YOUR_JWT_SECRET', algorithm='HS256'
   )
   ```

2. **Debug hook**: Add `window.__mapDebug = instance;` to `useMapInit.js` temporarily:
   ```js
   // In frontend/src/pages/maps/hooks/useMapInit.js
   // After: const instance = new maplibregl.Map({...});
   window.__mapDebug = instance;
   ```
   Remove it before committing.

3. **Playwright**: Install with `npx playwright install chromium` (first run only).

4. **Docker**: All services must be running (`docker compose up -d`).

## Access Points

| URL | What |
|-----|------|
| `http://localhost:4080` | Full stack via Caddy (use this for testing) |
| `http://localhost:3000` | Vite dev server (HMR, no Caddy) |
| `http://localhost:8077` | go-pmtiles tile server directly |

## Useful Diagnostics

**Check tile data schema** (what source-layers and kinds exist at a tile):
```bash
curl -s http://localhost:4080/api/tiles/master/$Z/$X/$Y.mvt -o _t.mvt
docker exec backend python3 -c "
import gzip; from mapbox_vector_tile import decode
d = open('/map-data/_t.mvt','rb').read()
try: d = gzip.decompress(d)
except: pass
dec = decode(d)
for ln, ld in dec.items():
    ks = set(f.get('properties',{}).get('kind','') for f in ld.get('features',[]))
    print(f'{ln}: {sorted(ks)}')
"
```

**Check tile HTTP status**:
```bash
curl -s -o /dev/null -w "HTTP %{http_code} Size: %{size_download}\n" \
  "http://localhost:4080/api/tiles/master/$Z/$X/$Y.mvt"
```

**Calculate tile coordinates for a lon/lat**:
```python
import math
lng, lat = 6.87, 45.92
for z in [7, 8, 10, 12]:
    x = int((lng + 180) / 360 * (2**z))
    rad = math.radians(lat)
    y = int((1 - math.log(math.tan(rad) + 1/math.cos(rad)) / math.pi) / 2 * (2**z))
    print(f'z{z}: {x}/{y}')
```

## Common Issues

- **Blank/white map**: Check tile HTTP status. 204 = tile not in archive. Check `map-data/` for master.pmtiles.
- **WebGL errors in headless**: Use SwiftShader flags (see args above). WebGL readPixels may return all zeros — the `page.screenshot()` captures the actual rendered output.
- **Tile 404s**: go-pmtiles expects correct file extension matching archive type (.mvt for MVT, .webp for WebP, .png for PNG).
- **Caddy issues**: `docker restart tracks-caddy` if tile routing breaks. Check logs: `docker logs tracks-caddy --tail 20`.
- **Auth failure**: Token key is `tracks_token` in localStorage. Must match a valid user in the database.
