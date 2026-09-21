"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { PROVIDERS, PROVIDER_MAP, makeAgent } from "@/lib/providers";

const STORAGE_KEY = "ai-chatroom-web-v1";
const KEY_STORAGE = "ai-chatroom-web-keys-v1";
const MAX_AGENTS = 8;

const starterAgents = [
  { id: "starter-groq", name: "Groq", avatar: "G", providerId: "groq", model: PROVIDER_MAP.groq.model, enabled: true, accent: PROVIDER_MAP.groq.accent, apiKey: "" },
  { id: "starter-openrouter", name: "OpenRouter", avatar: "OR", providerId: "openrouter", model: PROVIDER_MAP.openrouter.model, enabled: true, accent: PROVIDER_MAP.openrouter.accent, apiKey: "" },
  { id: "starter-google", name: "Gemini", avatar: "Gm", providerId: "google", model: PROVIDER_MAP.google.model, enabled: true, accent: PROVIDER_MAP.google.accent, apiKey: "" },
];

function newMessage(fields) {
  return { id: `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`, createdAt: Date.now(), error: false, ...fields };
}

function agentWithoutSecret(agent) {
  const { apiKey, ...safe } = agent;
  return safe;
}

export default function Home() {
  const [agents, setAgents] = useState(starterAgents);
  const [messages, setMessages] = useState([]);
  const [mode, setMode] = useState("friendly");
  const [draft, setDraft] = useState("");
  const [runningAgentId, setRunningAgentId] = useState("");
  const [mounted, setMounted] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(true);
  const abortRef = useRef(null);
  const endRef = useRef(null);

  const activeAgents = useMemo(() => agents.filter((agent) => agent.enabled), [agents]);
  const running = Boolean(runningAgentId);

  useEffect(() => {
    try {
      const stored = JSON.parse(localStorage.getItem(STORAGE_KEY) || "null");
      const keys = JSON.parse(sessionStorage.getItem(KEY_STORAGE) || "{}");
      if (stored?.agents?.length) setAgents(stored.agents.map((agent) => ({ ...agent, apiKey: keys[agent.id] || "" })));
      if (Array.isArray(stored?.messages)) setMessages(stored.messages.slice(-200));
      if (stored?.mode === "expert" || stored?.mode === "friendly") setMode(stored.mode);
    } catch {}
    setMounted(true);
  }, []);

  useEffect(() => {
    if (!mounted) return;
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ agents: agents.map(agentWithoutSecret), messages: messages.slice(-200), mode }));
    sessionStorage.setItem(KEY_STORAGE, JSON.stringify(Object.fromEntries(agents.filter((a) => a.apiKey).map((a) => [a.id, a.apiKey]))));
  }, [agents, messages, mode, mounted]);

  useEffect(() => { endRef.current?.scrollIntoView({ behavior: "smooth", block: "end" }); }, [messages, runningAgentId]);

  function updateAgent(id, patch) {
    setAgents((current) => current.map((agent) => (agent.id === id ? { ...agent, ...patch } : agent)));
  }

  function changeProvider(id, providerId) {
    const provider = PROVIDER_MAP[providerId];
    if (!provider) return;
    setAgents((current) => current.map((agent) => agent.id === id ? { ...agent, providerId, model: provider.model, avatar: provider.shortLabel, accent: provider.accent, apiKey: "" } : agent));
  }

  function setActiveCount(nextCount) {
    const count = Math.max(1, Math.min(MAX_AGENTS, Number(nextCount) || 1));
    setAgents((current) => {
      let next = [...current];
      let enabled = next.filter((agent) => agent.enabled).length;
      if (enabled < count) {
        next = next.map((agent) => {
          if (enabled >= count || agent.enabled) return agent;
          enabled += 1;
          return { ...agent, enabled: true };
        });
        while (enabled < count && next.length < MAX_AGENTS) { next.push(makeAgent(next.length)); enabled += 1; }
      } else if (enabled > count) {
        for (let index = next.length - 1; index >= 0 && enabled > count; index -= 1) {
          if (next[index].enabled) { next[index] = { ...next[index], enabled: false }; enabled -= 1; }
        }
      }
      return next;
    });
  }

  function addAgent() {
    setAgents((current) => current.length >= MAX_AGENTS ? current : [...current, makeAgent(current.length)]);
  }

  function removeAgent(id) {
    setAgents((current) => {
      if (current.length <= 1) return current;
      const next = current.filter((agent) => agent.id !== id);
      if (!next.some((agent) => agent.enabled)) next[0] = { ...next[0], enabled: true };
      return next;
    });
  }

  async function sendMessage(event) {
    event?.preventDefault();
    const text = draft.trim();
    if (!text || running || activeAgents.length === 0) return;

    const userMessage = newMessage({ speakerId: "USER", speakerName: "You", text });
    let history = [...messages, userMessage];
    setMessages(history);
    setDraft("");

    const controller = new AbortController();
    abortRef.current = controller;
    const roster = activeAgents.map((agent) => agent.name).join(", ");
    const tone = mode === "expert" ? "Give technically precise answers with concrete steps, assumptions, and caveats when useful." : "Keep the discussion natural and direct. Prefer a few useful paragraphs over a long lecture.";
    const systemPrompt = `This room has these active AI participants: ${roster}. ${tone} Build on useful prior replies without repeating them unnecessarily.`;

    for (const agent of activeAgents) {
      if (controller.signal.aborted) break;
      setRunningAgentId(agent.id);
      if (!agent.apiKey.trim()) {
        history = [...history, newMessage({ speakerId: agent.id, speakerName: agent.name, text: "API key missing. Open this participant's settings and add a key.", error: true })];
        setMessages(history);
        continue;
      }
      try {
        const response = await fetch("/api/chat", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          signal: controller.signal,
          body: JSON.stringify({
            agent: { id: agent.id, name: agent.name, providerId: agent.providerId, model: agent.model },
            apiKey: agent.apiKey,
            history,
            systemPrompt,
            maxTokens: mode === "expert" ? 4096 : 2048,
          }),
        });
        const payload = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(payload.error || "Unable to get a reply.");
        history = [...history, newMessage({ speakerId: agent.id, speakerName: agent.name, text: payload.text })];
        setMessages(history);
      } catch (error) {
        if (controller.signal.aborted) break;
        history = [...history, newMessage({ speakerId: agent.id, speakerName: agent.name, text: error instanceof Error ? error.message : "Unable to get a reply.", error: true })];
        setMessages(history);
      }
    }
    setRunningAgentId("");
    abortRef.current = null;
  }

  function stopTurn() { abortRef.current?.abort(); setRunningAgentId(""); abortRef.current = null; }
  function clearChat() { stopTurn(); setMessages([]); }
  function clearKeys() { setAgents((current) => current.map((agent) => ({ ...agent, apiKey: "" }))); sessionStorage.removeItem(KEY_STORAGE); }

  return (
    <main className="appShell">
      <header className="topBar">
        <div className="brandBlock"><div className="brandMark">AI</div><div><h1>AI Chatroom</h1><p>One room. Multiple models. Your rules.</p></div></div>
        <div className="topActions"><span className="privacyPill">Keys stay in this browser tab</span><button className="ghostButton mobileSettings" onClick={() => setSettingsOpen((value) => !value)}>{settingsOpen ? "Hide setup" : "Room setup"}</button></div>
      </header>

      <section className={`workspace ${settingsOpen ? "settingsVisible" : ""}`}>
        <aside className="settingsPanel">
          <div className="panelHeading"><div><span className="eyebrow">ROOM SETUP</span><h2>Participants</h2></div><span className="countBadge">{activeAgents.length} active</span></div>
          <div className="controlCard compactControl"><div><label>How many AIs?</label><span className="hint">1–{MAX_AGENTS} active participants</span></div><div className="stepper"><button onClick={() => setActiveCount(activeAgents.length - 1)} disabled={activeAgents.length <= 1}>−</button><strong>{activeAgents.length}</strong><button onClick={() => setActiveCount(activeAgents.length + 1)} disabled={activeAgents.length >= MAX_AGENTS}>+</button></div></div>
          <div className="modeSwitch" role="group" aria-label="Reply style"><button className={mode === "friendly" ? "selected" : ""} onClick={() => setMode("friendly")}>Friendly</button><button className={mode === "expert" ? "selected" : ""} onClick={() => setMode("expert")}>Expert</button></div>

          <div className="agentList">
            {agents.map((agent, index) => {
              const provider = PROVIDER_MAP[agent.providerId];
              const isRunning = runningAgentId === agent.id;
              return (
                <article className={`agentCard ${agent.enabled ? "enabled" : "disabled"}`} key={agent.id}>
                  <div className="agentSummary"><div className="avatar" style={{ "--agent-accent": agent.accent }}>{agent.avatar || index + 1}</div><div className="agentIdentity"><input className="nameInput" aria-label="Participant name" value={agent.name} maxLength={30} onChange={(event) => updateAgent(agent.id, { name: event.target.value })}/><span>{isRunning ? "Replying…" : provider?.label}</span></div><label className="toggle"><input type="checkbox" checked={agent.enabled} onChange={(event) => { if (!event.target.checked && activeAgents.length <= 1) return; updateAgent(agent.id, { enabled: event.target.checked }); }}/><span /></label></div>
                  <div className="agentFields">
                    <label>Provider<select value={agent.providerId} onChange={(event) => changeProvider(agent.id, event.target.value)}>{PROVIDERS.map((item) => <option key={item.id} value={item.id}>{item.label}</option>)}</select></label>
                    <label>Model<input value={agent.model} onChange={(event) => updateAgent(agent.id, { model: event.target.value })}/></label>
                    <label>API key<input type="password" autoComplete="off" placeholder="Stored only for this tab" value={agent.apiKey} onChange={(event) => updateAgent(agent.id, { apiKey: event.target.value })}/></label>
                  </div>
                  <div className="agentFooter"><small>{provider?.note}</small><button className="textButton danger" onClick={() => removeAgent(agent.id)} disabled={agents.length <= 1}>Remove</button></div>
                </article>
              );
            })}
          </div>
          <button className="addAgentButton" onClick={addAgent} disabled={agents.length >= MAX_AGENTS}>+ Add another AI</button>
          <div className="settingsFooter"><button className="textButton" onClick={clearKeys}>Clear API keys</button><span>History is saved locally on this device.</span></div>
        </aside>

        <section className="chatPanel">
          <div className="chatToolbar"><div><span className="eyebrow">LIVE ROOM</span><h2>{activeAgents.map((agent) => agent.name || "AI").join(" · ")}</h2></div><button className="ghostButton" onClick={clearChat} disabled={!messages.length && !running}>Clear chat</button></div>
          <div className="messageArea">
            {messages.length === 0 ? (
              <div className="emptyState"><div className="orbitalGraphic"><span className="orbit one"/><span className="orbit two"/><span className="core">AI</span></div><span className="eyebrow">MULTI-MODEL CHAT</span><h3>Ask once. Let the room discuss.</h3><p>Choose how many AIs join the room, give each one a provider and model, then send a single prompt. Replies arrive in order and later AIs can see what earlier AIs said.</p><div className="promptChips">{["Compare two design options", "Review a code idea", "Brainstorm and critique", "Explain a technical problem"].map((prompt) => <button key={prompt} onClick={() => setDraft(prompt)}>{prompt}</button>)}</div></div>
            ) : (
              <div className="messageList">
                {messages.map((message) => {
                  const isUser = message.speakerId === "USER";
                  const agent = agents.find((item) => item.id === message.speakerId);
                  return <article className={`messageRow ${isUser ? "userRow" : "aiRow"}`} key={message.id}>{!isUser && <div className="messageAvatar" style={{ "--agent-accent": agent?.accent || "#64748b" }}>{agent?.avatar || "AI"}</div>}<div className={`messageBubble ${message.error ? "errorBubble" : ""}`}><div className="messageMeta"><strong>{message.speakerName}</strong>{!isUser && <span>{PROVIDER_MAP[agent?.providerId]?.label || "AI"}</span>}{!isUser && !message.error && <button onClick={() => navigator.clipboard?.writeText(message.text)}>Copy</button>}</div><div className="messageText">{message.text}</div></div></article>;
                })}
                {running && <article className="messageRow aiRow typingRow"><div className="messageAvatar"><span className="typingDot"/></div><div className="messageBubble typingBubble"><strong>{agents.find((agent) => agent.id === runningAgentId)?.name || "AI"}</strong><span className="typing"><i/><i/><i/></span></div></article>}
              </div>
            )}
            <div ref={endRef}/>
          </div>
          <form className="composer" onSubmit={sendMessage}>
            <textarea value={draft} onChange={(event) => setDraft(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); sendMessage(event); } }} rows={1} placeholder="Message the room…" disabled={running}/>
            {running ? <button type="button" className="stopButton" onClick={stopTurn}>Stop</button> : <button type="submit" className="sendButton" disabled={!draft.trim() || activeAgents.length === 0}>Send</button>}
            <div className="composerHint">Enter to send · Shift+Enter for a new line · {activeAgents.length} AI{activeAgents.length === 1 ? "" : "s"} will reply</div>
          </form>
        </section>
      </section>
    </main>
  );
}
