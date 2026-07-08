import React from "react";
import ReactDOM from "react-dom/client";
import { ThemeContextProvider, getVivoSkin } from "@telefonica/mistica";
import App from "./App";
import "./theme-vivo.css";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ThemeContextProvider theme={{ skin: getVivoSkin(), i18n: { locale: "pt-BR", phoneNumberFormattingRegionCode: "BR" } }}>
      <App />
    </ThemeContextProvider>
  </React.StrictMode>
);
