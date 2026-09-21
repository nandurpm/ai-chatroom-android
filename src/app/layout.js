import "./globals.css";

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
