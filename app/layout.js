// Vercel/Next.js compatibility root layout.
// Keep the full web application under web/ while allowing a Vercel project whose Root Directory is the repository root to serve it.
import "../web/src/app/globals.css";

export const metadata = {
  title: "AI Chatroom",
  description: "Run a configurable room where multiple AI models discuss your prompt together.",
};

export default function RootLayout({ children }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
