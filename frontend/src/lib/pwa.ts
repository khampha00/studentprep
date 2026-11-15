import { registerSW } from 'virtual:pwa-register'

const UPDATE_CHECK_INTERVAL_MS = 15 * 60 * 1000

let initialized = false
let pendingReload = false
let reloadScheduled = false

/**
 * Returns true when the current path belongs to an active exam sitting where an
 * automatic page reload would interrupt the candidate or trigger beforeunload prompts.
 */
export function isExamRoute(pathname: string = window.location.pathname): boolean {
  return pathname === '/exam' || pathname.startsWith('/exam/')
}

function performReload(): void {
  if (reloadScheduled) {
    return
  }
  reloadScheduled = true
  window.location.reload()
}

function requestReloadOrDefer(): void {
  if (isExamRoute(window.location.pathname)) {
    pendingReload = true
    return
  }
  pendingReload = false
  performReload()
}

function flushPendingReload(): void {
  if (pendingReload && !isExamRoute(window.location.pathname)) {
    pendingReload = false
    performReload()
  }
}

/**
 * Registers the PWA Service Worker and reloads non-exam tabs whenever an updated
 * build activates so returning browsers do not stay on stale cached bundles.
 */
export function initPwaUpdates(): void {
  if (initialized || typeof window === 'undefined' || !('serviceWorker' in navigator)) {
    return
  }
  initialized = true

  // Capture the controller present before registration so first-time installs do not reload,
  // while genuine upgrades (old controller -> new controller) always pick up fresh assets.
  let activeController = navigator.serviceWorker.controller
  let activeRegistration: ServiceWorkerRegistration | undefined

  const checkForUpdate = (): void => {
    if (!activeRegistration || navigator.onLine === false || isExamRoute(window.location.pathname)) {
      return
    }
    void activeRegistration.update().catch(() => {
      // Ignore transient offline or network failures during background update checks.
    })
  }

  navigator.serviceWorker.addEventListener('controllerchange', () => {
    const nextController = navigator.serviceWorker.controller
    const wasUpgraded =
      activeController !== null &&
      nextController !== null &&
      activeController !== nextController

    activeController = nextController
    if (wasUpgraded) {
      requestReloadOrDefer()
    }
  })

  window.addEventListener('popstate', flushPendingReload)
  window.navigation?.addEventListener('navigatesuccess', flushPendingReload)

  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
      flushPendingReload()
      checkForUpdate()
    }
  })

  registerSW({
    immediate: true,
    onNeedReload: requestReloadOrDefer,
    onRegisteredSW: (_swUrl, registration) => {
      activeRegistration = registration
      if (registration) {
        window.setInterval(checkForUpdate, UPDATE_CHECK_INTERVAL_MS)
      }
    },
  })
}
