// @ts-check
import { defineConfig } from "astro/config";
import mdx from "@astrojs/mdx";
import sitemap from "@astrojs/sitemap";

// https://astro.build/config
export default defineConfig({
  site: "https://getghostcrab.com",
  trailingSlash: "ignore",
  integrations: [
    mdx(),
    sitemap({
      filter: (page) => !page.includes("/_"),
      changefreq: "monthly",
      priority: 0.7,
    }),
  ],
  build: {
    inlineStylesheets: "auto",
  },
  vite: {
    build: {
      cssMinify: "lightningcss",
    },
  },
});
