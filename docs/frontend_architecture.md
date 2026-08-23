# StudentPrep Frontend Architecture & PWA Strategy

This document defines the strict frontend stack and offline-first Progressive Web App (PWA) configuration required for the StudentPrep CBT platform. 

## 1. Core Stack Definitions

*   **Build Tool:** Vite (chosen for HMR speed and seamless PWA plugin integration).
*   **Framework:** React 18 (Strict Mode enabled).
*   **Routing:** React Router v6 (Client-side routing).
*   **Language:** TypeScript (Strict typing mandatory).
*   **Styling:** Tailwind CSS + shadcn/ui.
*   **Typography:** 'Inter' font (as mandated by UI standards).

## 2. Progressive Web App (PWA) Configuration

To survive total network disconnects during an exam without disrupting the student, the frontend must operate as a fully offline-capable PWA.

### 2.1 `vite-plugin-pwa` Setup
The `frontend-state-architect` will configure Vite to use `vite-plugin-pwa` with the `generateSW` strategy.
*   **Asset Pre-caching:** All HTML, JS, CSS, and KaTeX font files MUST be pre-cached when the student first logs in. This ensures that if the network drops before the exam starts, the UI can still mount and render the exam layout.
*   **Manifest:** A robust `manifest.webmanifest` must be provided, defining the app as `display: standalone` to prevent browser UI (like the back button) from interfering with the exam space.

### 2.2 Service Worker Responsibilities
The Service Worker has two distinct jobs:
1.  **Stale-While-Revalidate:** For static assets (images, fonts, bundles), serve from cache immediately, then update the cache in the background.
2.  **Background Sync API:** While `Dexie.js` handles the local queuing of exam answers, the Service Worker must register for the Background Sync API (if supported by the browser) to push the final exam payload to `/api/v1/exams/active/submit` if the student closes the tab while offline and later reconnects.

## 3. UI/UX Standards Enforcement

The `frontend-ui-developer` must adhere to these mandated rules:

### 3.1 Color & Contrast (Eye-Strain Reduction)
*   **Backgrounds:** Pure white (`#ffffff`) is strictly forbidden. Use `bg-slate-50` or `bg-zinc-50` to reduce eye strain during a 2-hour exam.
*   **Primary Brand:** JAMB Green (`#008751`).
*   **Text:** `text-slate-900` for high-contrast readability.

### 3.2 Component Rules
*   **Required Fields:** All required form labels MUST have a red asterisk directly after the text. It must use `<span className="-ml-2 text-[18px] font-bold text-destructive">*</span>` to zero out the gap.
*   **Dates:** Raw `<input type="date">` is forbidden. Always use shadcn/ui `<Popover>` + `<Calendar>`.
*   **Complex Layouts:** Detail views must use a compact 2-column grid (`grid-cols-2 gap-y-2`). Sections with more than 2 distinct groups must be wrapped in a shadcn `<Accordion>`.

## 4. LaTeX & Math Rendering

Because math and chemistry questions contain complex equations, we will use **KaTeX** (via `react-katex`) rather than MathJax. 
*   **Why KaTeX?** It renders synchronously and is significantly faster, preventing layout shifts (Cumulative Layout Shift) when a student navigates to a new question. 
*   **Sanitization:** All JSONB text containing LaTeX must be sanitized (e.g., using DOMPurify) before rendering to prevent XSS attacks if an Admin mistakenly pastes malicious HTML into the question bank.
