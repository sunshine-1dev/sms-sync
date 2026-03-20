import { ref } from "vue";
import type { CodeMessage } from "../types";

const MAX_CODES = 50;
const codes = ref<CodeMessage[]>([]);

export function useCodeStore() {
  function addCode(msg: CodeMessage) {
    codes.value = [msg, ...codes.value].slice(0, MAX_CODES);
  }

  function clearCodes() {
    codes.value = [];
  }

  return { codes, addCode, clearCodes };
}
