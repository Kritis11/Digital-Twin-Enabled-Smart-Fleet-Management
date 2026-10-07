import { settings } from './settings';

// Development (ng serve): the backend runs separately on port 8080.
export const environment = {
  apiBaseUrl: 'http://localhost:8080',
  wsUrl: 'ws://localhost:8080/ws',
  ...settings,
};
