export const environment = {
  apiBaseUrl: 'http://localhost:8080',
  wsUrl: 'ws://localhost:8080/ws',
  wsReconnectMs: 3000,
  // How often the Vehicle Detail charts re-fetch their time range.
  chartRefreshMs: 10000,
  // Health score bands: above greenAbove is green, from amberFrom up to it is amber, below is red.
  health: { greenAbove: 90, amberFrom: 60 },
  map: {
    tileUrl: 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
    attribution: '&copy; OpenStreetMap contributors',
    center: [20.5, 78.9] as [number, number],
    zoom: 5,
  },
};
