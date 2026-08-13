import "@fontsource/noto-sans-sc/chinese-simplified-400.css";
import "@fontsource/noto-sans-sc/chinese-simplified-600.css";
import "@fontsource/ibm-plex-mono/400.css";
import "./styles.css";
import { createRoot } from "react-dom/client";
import { AccessApp } from "./AccessApp";

createRoot(document.getElementById("root")!).render(<AccessApp />);
