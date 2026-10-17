export const environment = {
  auth0: {
    domain: 'dev-lk4v4yeoogauzwyr.us.auth0.com',
    clientId: 'VXOx3TrDK15L7XPejXwXYFQEtpRqXYlH',
    audience: 'https://api.runevault',
  },
  // Live transfer events. A path resolves against the page's own host as ws:// or wss://, so dev
  // (Angular proxy), Docker (nginx) and the HTTPS deployment (Caddy) all use the same build.
  // Set to '' to turn live updates off; the dashboard then falls back to polling.
  feedUrl: '/ws/transfers',
};
