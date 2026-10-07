export const environment = {
  apiBaseUrl: 'http://localhost:8080',
  wsUrl: 'ws://localhost:8080/ws',
  wsReconnectMs: 3000,
  // How often the Vehicle Detail charts re-fetch their time range.
  chartRefreshMs: 10000,
  // How often driver, trip, fuel and recommendation data is re-fetched.
  insightsRefreshMs: 30000,
  // Remaining-useful-life colours: at or below urgentDays is red, at or below soonDays amber. barMaxDays is the bar scale.
  rul: { urgentDays: 3, soonDays: 10, barMaxDays: 60 },
  // Health score bands: above greenAbove is green, from amberFrom up to it is amber, below is red.
  health: { greenAbove: 90, amberFrom: 60 },
  // Route Planner: one colour per vehicle route, reused in order if there are more routes.
  routeColours: ['#1f5fbf', '#c0362c', '#1a7f37', '#7c3aed', '#d97706', '#0e7490', '#be185d'],
  map: {
    tileUrl: 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
    attribution: '&copy; OpenStreetMap contributors',
    center: [20.5, 78.9] as [number, number],
    zoom: 5,
  },
};
