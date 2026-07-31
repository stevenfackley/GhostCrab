# GhostCrab: Technical Branding & UI System

## 1. Brand Identity & Positioning
GhostCrab is the lightweight, mobile-first extension of the OpenClaw ecosystem. Its branding reflects its architectural reality: a fast, stateless thin client that leaves no footprint. It is the translucent interface layer connecting the user to the heavy lifting performed by the OpenClaw Gateway.

* **Core Concepts:** Speed, Translucency, Precision, Ephemerality.
* **Relationship to Parent Brand:** A specialized operational utility within the OpenClaw suite. It does not replace OpenClaw; it commands it.

## 2. Visual Identity & Logo Design
The GhostCrab logo is an evolution of the core OpenClaw visual identity, modified to communicate its distinct purpose.

* **Form:** Retains the fundamental claw silhouette of the OpenClaw logo, ensuring immediate brand recognition. 
* **Modifications:** * **Structural:** Subtle addition of elevated, periscopic "eye stalks" at the upper apex of the geometry, mimicking the distinct anatomy of a ghost crab and symbolizing network discovery/scanning (mDNS).
    * **Rendering:** Stripped of heavy, solid fills. Executed as a crisp, vector-based wireframe or outline.
* **Materiality:** The icon utilizes an alpha channel to create a translucent, "phantom" appearance against different backgrounds.

## 3. Color Palette
The color system moves away from heavy, saturated tones in favor of a luminous, high-contrast palette suited for a technical utility application.

* **Primary Brand Color: Luminous Cyan / Pale Azure**
    * Represents the bioluminescent, aquatic theme and the "ghostly" nature of the app. Used for primary actions, active connections, and key focal points.
* **Backgrounds: Deep Abyss (Dark Mode Default)**
    * A rich, near-black dark theme (e.g., `#0F1115` or `#121212`) to reduce eye strain, conserve battery, and allow the translucent UI elements to stand out.
* **Surface Layers: Glassmorphism / Alpha Overlays**
    * Instead of solid gray cards, surface elements (like connection profiles or config sections) utilize 5-10% opacity white overlays over the dark background, maintaining the "no solid footprint" concept.
* **Status Indicators:**
    * *Scanning/mDNS:* Pulsing pale blue.
    * *Connected:* Solid, bright cyan.
    * *Disconnected/Unreachable:* Muted amber or stark gray.
    * *Error (Auth/Status):* High-contrast crimson outline.

## 4. Typography
The typography system is designed for a developer-centric configuration tool, prioritizing readability and data density.

* **Primary UI Font:** A clean, modern sans-serif (e.g., *Inter*, *Roboto*, or *San Francisco*) for all standard interface elements (buttons, navigation, headers).
* **Monospace Font:** A high-legibility monospace font (e.g., *JetBrains Mono*, *Fira Code*, or *Roboto Mono*) used strictly for:
    * Displaying IP addresses and ports.
    * Editing `openclaw.json` configurations.
    * Rendering AI CLI outputs and code snippets.

## 5. UI/UX Principles (Jetpack Compose)
The interface is utilitarian and strictly functional. 

* **No Frivolous Animation:** Animations should be restricted to state changes (e.g., expanding a config accordion, the pulse of a network scan). Transitions are snappy, reflecting the app's speed.
* **Data Density over Whitespace:** While maintaining touch-target standards for Android, the UI prioritizes showing as much configuration data and model status as possible without excessive scrolling.
* **State Transparency:** Always explicitly display the current connection state, auth mode, and network layer. If the app is connecting over HTTP (unencrypted LAN), visually flag this for the user.

## 6. App Store Meta & Voice
* **Title:** GhostCrab
* **Subtitle:** Remote Client for OpenClaw
* **Voice & Tone:** Direct, objective, and technical. The app does not market itself with buzzwords; it states its exact capabilities. Error messages are explicit, exposing HTTP status codes and exact failure points (e.g., "Gateway unreachable at 192.168.1.50:18789" rather than "Oops, something went wrong").