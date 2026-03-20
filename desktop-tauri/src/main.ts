import { createApp } from "vue";
import { createVuetify } from "vuetify";
import "vuetify/styles";
import "@mdi/font/css/materialdesignicons.css";
import App from "./App.vue";

const vuetifyInstance = createVuetify({
  theme: {
    defaultTheme: "light",
    themes: {
      light: {
        colors: {
          primary: "#007AFF",
          secondary: "#5856D6",
          success: "#34C759",
          error: "#FF3B30",
          warning: "#FF9500",
          background: "#F5F5F7",
          surface: "#FFFFFF",
        },
      },
    },
  },
  defaults: {
    VBtn: {
      rounded: "lg",
      elevation: 0,
    },
    VTextField: {
      variant: "outlined",
      density: "compact",
      rounded: "lg",
    },
  },
});

createApp(App).use(vuetifyInstance).mount("#app");
