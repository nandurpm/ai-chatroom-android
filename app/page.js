// Vercel/Next.js compatibility entrypoint.
// The actual web client stays isolated in web/ so the Android Gradle module can remain at app/.
export { default } from "../web/src/app/page";
