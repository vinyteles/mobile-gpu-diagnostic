# Shared RTL sessions

Use a separate public Hugging Face dataset repository for each RTL reservation.
Each participant uses their own Samsung Developer account, HF account, and repository-scoped write token.

Create it in the HF website, or run:

```sh
python scripts/create-hf-session.py --batch 1 --device 'Galaxy S25 FE / Exynos 2400' --manifest reports/my-session.json
```

Share the public repository URL in a session issue or add the generated manifest here through a pull request.
Do not include tokens in issues, manifests, commits, or screenshots.

The default benchmark has 30 base questions in four wording variants. Every configuration
runs those same 120 inputs. Parallel assignment speeds up coverage, but different SoCs,
RAM sizes, runtimes, heat and session restarts must remain separate in timing comparisons.
Even with the same hardware, repeated sessions should retain their run identities.
