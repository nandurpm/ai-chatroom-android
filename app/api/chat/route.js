// Vercel/Next.js compatibility API route for repository-root deployments.
// The canonical implementation remains under web/src/app/api/chat/route.js.
export const runtime = "nodejs";
export const maxDuration = 90;
export { GET, POST } from "../../../web/src/app/api/chat/route";
