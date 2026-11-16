# From working prototype to selling it (for a software engineer new to hardware)

Context: India-only first, power from battery **and** bike, round display. Companion docs:
`HARDWARE_ROADMAP.md` (hardware research), `HARDWARE_PCB_GUIDE.md` (parts and PCB basics).

Items marked **(verify)** are from general knowledge or vendor/search summaries — confirm with an official
source, a CA/lawyer, or a compliance consultant before spending money.

---

## 1. The mental model: hardware releases work like software releases, but you can't hot-fix

| Software | Hardware equivalent | What it means |
|----------|--------------------|---------------|
| Proof of concept | **Prototype** (you are here) | Works on a bench |
| Alpha | **EVT** (engineering validation) | First custom PCB, 5–10 units; does it work at all? |
| Beta | **DVT** (design validation) | Near-final design; rugged, waterproof, environmental tests, 30–50 units to real riders |
| Release candidate | **PVT** (production validation) | Made on the real production line, 100–500 units; test the *process* |
| GA | **MP** (mass production) | Repeat orders |
| Dependency list | **BOM** | Every part, supplier, price, lead time |
| Build artifacts | **Gerber + assembly files** | What the factory builds from |
| CI tests | **Factory test jig** | Every unit is tested before packing |
| Deploy | **OTA update** | The only part you *can* fix after shipping |

**Big difference:** a bug in a mould or a PCB costs weeks and money, and shipped devices can't be patched
except by firmware. So you validate *before* spending on tooling and inventory.

---

## 2. Step by step

### Step 0: Decide it's worth building (2–4 weeks, almost no cost)
- Talk to **20+ riders**: do they want this, and at what price? What do they use today (phone mount, Beeline)?
- Put up a **landing page and take refundable pre-orders / a waitlist** (own website with an Indian payment gateway; crowdfunding if suitable **(verify)**).
- Buy and study a **Beeline Moto II** (and one more device): price, weight, battery, size, app, reviews, complaints.
- **Gate:** enough serious pre-order interest at a price that covers your costs. If not, stop or change the product.

### Step 1: Lock the product spec (2–4 weeks)
Write a one-page spec: target price, size/weight, display, battery life (market reference ~14 h), IP rating, mount types,
supported bikes/phones, what is in the box. Decide the **business model**: one-time price, or price + subscription
to cover the per-ride Google cost (India: about $0.008 per destination after 7,000 free per month).

### Step 2: Prove the risky unknowns (4–6 weeks, run in parallel)
From `HARDWARE_ROADMAP.md §4`: sunlight display test, power measurement, locked-screen test on several phones,
ride/vibration test, Beeline teardown, Google cost and terms. Result: display + chip + battery decisions and a costed BOM.

### Step 3: Build the team and partners (start in Step 0)
You don't have to know everything. You do need access to:
- an **electronics/PCB engineer** (a freelancer to *design or review* the first board is enough to start),
- a **mechanical/industrial designer** (case, gaskets, mount),
- a **compliance consultant** (WPC, BIS, labels),
- a **manufacturing partner**: PCB assembler or EMS; later a mould maker.
Look for hardware incubators and labs near you. Maker Village (Kochi) is a Kerala hardware incubator
**(verify)**; the Kerala Startup Mission may offer prototyping access and grants **(verify)**.

### Step 4: EVT, the first custom board (2–3 months)
Own PCB: ESP32-class pre-certified module + round display connector + USB-C + battery charging + IMU + buttons.
3D-printed case. Order 5–10 boards. Get a design review before ordering. **Gate:** one unit runs a full day on a bike.

### Step 5: DVT, a rugged, near-final design (2–3 months)
Real case and gasket (CNC/urethane casting, not injection tooling yet), bike hardwire cable, IP67 and vibration tests
at a lab, firmware hardened (see §3). 30–50 units to beta riders. **Gate:** passes environmental tests; riders keep using it.

### Step 6: Compliance, in parallel with DVT (do not leave it to the end)
| Item | Notes |
|------|-------|
| **WPC ETA** (radio) | Required to import/sell/use wireless devices in de-licensed bands; allowed by **self-declaration** on the Saral Sanchar portal (DoT). Vendor blogs say 2–6 weeks **(verify)** |
| **BIS CRS: battery** | Lithium cells/batteries for portable use are under BIS compulsory registration (IS 16046 Part 2); whether your *finished product* needs its own registration is **not settled (verify)** |
| **Bluetooth SIG listing** | Needed to use the Bluetooth brand; a pre-certified module helps **(verify)** |
| **Vibration / IP67 tests** | Accredited lab (IEC 60068-2-64 vibration; IEC 60529 ingress) |
| **E-waste and battery producer obligations** | India has e-waste and battery waste rules with producer responsibility registration **(verify applicability)** |
| **Legal Metrology labelling** | Packaged goods must show manufacturer/importer name and address, MRP incl. taxes, date of manufacture/import, consumer-care contact, country of origin **(verify)** |
| **If wired to the bike** | Vehicle EMC/safety rules may apply **(verify; not researched)** |
| **Google terms** | Confirm Navigation SDK guidance may be shown on your hardware in a *product* |

### Step 7: PVT, pilot production (2–3 months)
Pick the manufacturer, freeze the BOM, order parts (**long lead times are common, plan for weeks to months** **(verify)**),
build a **factory test jig**, make 100–500 units, run quality checks (sample inspection), do real packaging.
**Gate:** defect and yield targets met; first units survive shipping.

### Step 8: Launch and sell
- Channels: your own website, Amazon India / Flipkart, motorcycle accessory dealers and clubs. Pre-orders first.
- App on the **Play Store** (one-time fee about $25); iOS later (Apple's developer programme is yearly).
- **Warranty, returns and support**: a defined warranty period, replacement process, a support email/WhatsApp, a FAQ.
- Ship fixes by **OTA**; keep a **spare stock** for returns.

---

## 3. Production-readiness for the software side

**Firmware**
- OTA updates with signed images and rollback; secure boot and flash encryption (irreversible, see `HARDWARE_ROADMAP.md §K`).
- Proper Bluetooth security/bonding, device identity, factory-test mode, serial-number provisioning, crash/log counters.
- Battery safety: charge limits, temperature cutoff, safe low-battery behaviour.
- Reconnect robustness (the pairing issue we hit with Android's system pairing becomes a **support** issue at scale).

**Android app**
- Release build, signing, ProGuard/R8, crash reporting, analytics with consent.
- **Play Store declarations:** background location and foreground-service use need justification; a privacy policy and the Data Safety form **(verify)**.
- Location history is sensitive personal data: India's Digital Personal Data Protection Act applies **(verify current status/rules)**.
- Cost controls: Google quota and budget alerts, per-ride cost monitoring, a plan B routing engine.
- Support tools: in-app "send diagnostics", firmware update screen, clear Bluetooth help.

---

## 4. Business set-up checklist (India) **(verify with a CA/lawyer)**
- Company registration (Pvt Ltd/LLP), PAN, current bank account, **GST registration**.
- **Udyam (MSME)** registration; **DPIIT Startup India** recognition if eligible.
- **IEC** (import-export code) if you import components or finished goods.
- **Trademark** the name/logo; consider design registration for the enclosure.
- Contracts with the manufacturer: ownership of design files and tooling, quality terms, IP protection.

---

## 5. The shape of the money (fill with real quotes)
- **Prototype and EVT:** parts, boards, test tools, a freelancer's review.
- **DVT:** case prototypes, lab tests, beta units.
- **Compliance and labelling:** consultant plus testing fees.
- **Tooling** (injection moulds): the largest one-time item. **Delay it until DVT passes.**
- **Inventory:** units × landed cost. Example from our rough BOM estimate: 500 units × ~US$60 ≈ US$30,000 before shipping, duty, packaging, marketing **(estimate)**.
- **Running costs:** Google per-ride cost, support, returns, app and cloud tools.
- **Pricing rule of thumb:** many hardware products sell at roughly 3× landed cost to cover channel margin, warranty and marketing **(rule of thumb; verify for your market)**.

Funding options: pre-orders, savings, a small seed round, grants/incubator support, customer-funded pilot batch.

---

## 6. Biggest risks and how to cut them
| Risk | What to do |
|------|------------|
| Nobody wants it at your price | Step 0, pre-orders before spending |
| Display unreadable in sun | Step 2 test before choosing |
| Locked-screen / background Bluetooth unreliable on many phones | Test early on 3+ brands |
| Waterproofing/vibration failures late | Test at DVT, not PVT |
| Certification or labelling surprises | Start compliance at DVT |
| Google terms or cost at scale | Confirm early; keep plan B routing |
| Cash tied up in inventory | Small first batch; pre-orders |
| Single supplier/part shortage | Second-source plan, pre-certified modules |
| Firmware cloned | Secure boot and flash encryption in production |

---

## 7. First 30 days: concrete actions
1. Write the **one-page product spec** and a target price range.
2. Interview **20 riders**; build a landing page with a waitlist.
3. Buy a Beeline Moto II and a round display test kit; start the **sunlight test**.
4. Buy the starter tools (`HARDWARE_PCB_GUIDE.md §6`).
5. Find a **freelance PCB engineer** to review the Stage 1 design, and a **mechanical designer**.
6. Get a first call with a **compliance consultant** and with **2–3 PCB assemblers/EMS** (ask about MOQ, lead time, testing).
7. Decide company structure with a CA; start registrations.
8. Run the **locked-screen test** on three phone brands.
