import { settings } from './settings';

// Production (ng build): the dashboard is served by the same nginx that proxies /api and /ws,
// so both are reached on the page's own origin.
export const environment = {
  apiBaseUrl: '',
  wsUrl: `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`,
  ...settings,
};
