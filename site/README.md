# getghostcrab.com — marketing site

Astro v5 static site. Three pages: `/`, `/privacy`, `/support`. Deployed to **Cloudflare Pages**.

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

## Deploy to Cloudflare Pages

One-time setup (in the Cloudflare dashboard):

1. **Workers & Pages → Create application → Pages → Connect to Git**
2. Select the `stevenfackley/GhostCrab` repo
3. Build settings:
   - Production branch: `main`
   - Framework preset: `Astro`
   - Build command: `cd site && npm install && npm run build`
   - Build output directory: `site/dist`
   - Root directory: `/` (leave blank)
4. Environment variables: none needed
5. **Save and Deploy** — first build takes ~2 min

Custom domain:

6. After the first successful deploy, **Custom Domains → Set up a custom domain → `getghostcrab.com`**
7. Cloudflare auto-detects the domain is on Cloudflare DNS and wires the CNAME — no manual record needed

Preview deploys per PR happen automatically once connected.

## Updating

Every push to `main` that touches `site/`, `docs/PRIVACY_POLICY_IOS.md`, or any imported asset
triggers a Cloudflare Pages rebuild. There is **no** GitHub Actions workflow for the site — Cloudflare
Pages is git-connected directly.
