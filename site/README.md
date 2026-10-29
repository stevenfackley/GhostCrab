# getghostcrab.com — marketing site

Astro 7 static site. Three pages: `/`, `/privacy`, `/support`. Deployed to **Cloudflare Workers Static Assets** by
`.github/workflows/site-deploy.yml`.

## Develop

```bash
cd site
npm install
npm run dev
# → open http://localhost:4321
```

## Build

```bash
npm run build      # → dist/
npm run preview    # serves dist/ locally
```

## Project structure

```
site/
├── public/                       # static assets served as-is from /
│   ├── favicon.ico
│   ├── favicon.png
│   ├── apple-touch-icon.png
│   ├── og-default.png            # 1024×500 OpenGraph image
│   └── robots.txt
├── src/
│   ├── layouts/
│   │   └── BaseLayout.astro      # canonical, OG, JSON-LD schema, theme color
│   ├── components/
│   │   ├── Header.astro
│   │   └── Footer.astro
│   ├── pages/
│   │   ├── index.astro           # home — SoftwareApplication schema
│   │   ├── privacy.astro         # renders ../../docs/PRIVACY_POLICY_IOS.md
│   │   └── support.astro
│   └── styles/
│       ├── tokens.css            # brand tokens (port of DesignTokens.swift / BrandTokens.kt)
│       └── global.css
├── astro.config.mjs              # sitemap, MDX, site URL
├── tsconfig.json
└── package.json
```

## Privacy policy is the canonical markdown

The `/privacy` page renders `docs/PRIVACY_POLICY_IOS.md` directly at build time. **Don't fork the
content into the site folder** — Astro imports the markdown via relative path so what ships to the
App Store Connect record (the URL in the privacy policy field) is byte-for-byte the same as what
GitHub renders.

## SEO foundations baked in

- Canonical URLs on every page (via `BaseLayout`)
- OpenGraph + Twitter Card meta on every page
- `SoftwareApplication` JSON-LD schema on the home page
- `WebPage` JSON-LD on privacy
- `ContactPage` JSON-LD on support
- Sitemap auto-generated at `/sitemap-index.xml` via `@astrojs/sitemap`
- `robots.txt` references the sitemap
- Brand tokens match the iOS / Android app exactly — same colors, same fonts, same spacing scale

Validate schema before pushing meaningful changes:
https://search.google.com/test/rich-results — paste your dev URL or a staging deploy URL.

## Deploy

The site is a Worker serving `site/dist` through Workers Static Assets (`wrangler.jsonc` at the repo
root: `assets.directory: ./site/dist`, plus a `custom_domain` route for `getghostcrab.com` that
provisions the DNS record on deploy). It is not a Cloudflare Pages project.

One-time setup: add the `CLOUDFLARE_API_TOKEN` repo secret (Cloudflare "Edit Cloudflare Workers"
token template). The `site-deploy` workflow then runs `npx wrangler@4 deploy` on pushes to `main`;
pull requests run the build only. Cloudflare's dashboard Git integration never produced a green
deploy and should stay disconnected so the two paths don't race.

## Updating

Every push to `main` that touches `site/`, `docs/PRIVACY_POLICY_IOS.md`, or any imported asset
runs the `site-deploy` GitHub Actions workflow (`.github/workflows/site-deploy.yml`), which builds `site/` and deploys it to Cloudflare Workers Static Assets (needs the `CLOUDFLARE_API_TOKEN` repo secret; PRs run the build only). The workflow header explains why it replaced Cloudflare's Git integration.
