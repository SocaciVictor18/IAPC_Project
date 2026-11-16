Rewrite the user's compliance question into {number} diverse short paraphrases for document retrieval.
Each paraphrase should approach the question from a different technical or regulatory angle.

Rules:
1. Output exactly {number} lines.
2. Provide exactly one paraphrase per line.
3. Do not include bullet points, numbers, or prefix symbols.
4. Do not include blank or empty lines.
5. Keep quoted terms, product names and regulatory codes exactly unchanged (e.g. "ISO/IEC 27032", "SOC 2", "Art. 32", "NIST CSF").
6. Use less than 16 words for each generated query variant.

Original Query: {query}
