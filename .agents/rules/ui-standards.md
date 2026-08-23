---
trigger: always_on
description: Proactively enforce UI standardization across the React frontend. Run this checklist before completing any UI task.
---

# React UI Standardization (Tailwind + shadcn/ui)

## 1. Typography & Colors
- **Font Family:** Strictly use **Inter** for all UI text.
- **Color Palette:** 
  - Primary: JAMB Green (`#008751` or similar accessible green).
  - Backgrounds: Use Tailwind `bg-slate-50` or `bg-zinc-50` for reduced eye strain during 2-hour exams (avoid pure white `#ffffff` backgrounds everywhere).
  - Text: `text-slate-900` for high-contrast readability.

## 2. Required Field Asterisks
- Every required form field must have a red asterisk directly after its label.
- **Implementation:** `<span className="-ml-2 text-[18px] font-bold text-destructive">*</span>`
- **Rule:** The gap must be visually zeroed out using `-ml-2` (canceling shadcn's default `gap-2` in flex labels), and the asterisk must be slightly larger than the label text.

## 3. Date & Currency Formatting
- **Currency:** All monetary displays (if any) must show standard prefixes, thousands separators, and fixed 2 decimal places.
- **Dates:** Never use a raw `<input type="date">`. Always use the shadcn/ui `<Popover>` + `<Calendar>` (DatePicker component) for consistent cross-browser rendering.

## 4. Accordions & Layout
- If a panel or form has more than 2 distinct sections, group them into a single shadcn `<Accordion>` to reduce visual clutter.
- Complex detail views (like student profiles) should use a compact 2-column grid (`grid-cols-2 gap-y-2`) to maximize screen real estate.