# Changelog

Bugs found and fixed during the final review (branch `final-review`). No features were added.

| Found by | Problem | Fix |
|---|---|---|
| End-to-end test (admin journey) | On User Management, error messages such as "Password must be at least 10 characters" or "That username is taken" never appeared: the list reload that follows a failed change cleared the message straight away. | The message is now cleared when the next action starts, not by the reload (`frontend/src/app/users/users.ts`). |
| End-to-end test (route planning, production stack) | When some vehicles were fit but no stop could be reached within the 24-hour route limit, the Route Planner showed only the note about OSRM ("OSRM not used: …") and no explanation of why there were no routes. | The page now distinguishes "no vehicle is fit" from "no stop is reachable" and says which (`frontend/src/app/route-planner/route-planner.ts`). |
