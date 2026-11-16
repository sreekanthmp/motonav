# MotoNav: product spec v0.1, rider interviews and landing page

Step 0/1 of `GO_TO_MARKET_PLAN.md`. This is a **draft to be tested with riders**, not a final spec.
Values marked **TEST** are targets or assumptions that the experiments or interviews must confirm.
Prices are examples; check live prices and today's rupee rate.

---

## 1. One-sentence product

A small, rugged, round display on the handlebar that shows your **next turn, distance and street name**,
driven by the navigation app on your phone. Phone in the pocket, eyes on the road.

## 2. Who it is for (hypotheses to test)

| Segment | Why they might buy | Watch-out |
|---------|--------------------|-----------|
| Daily commuters | Navigation without a phone mount in the rain/glare | Price sensitivity |
| Touring / adventure riders | Long rides, unfamiliar roads, want phone safe in a pocket | Need long battery life and rugged build |
| Delivery riders | Many destinations a day | **High Google cost** per user (see §6) |
| Riders with a phone-mount problem | Vibration damages phones | Need a secure mount |

Interview all four; decide which one to build for first.

## 3. Decisions already made

- Phone does the navigation (phone GPS, Google Navigation SDK, two-wheeler routing in India); the device only displays.
- Bluetooth LE link; **Android first**, iPhone later.
- Power: **internal battery and bike power**.
- Round display; **India only** for now.
- Destination set by **sharing a place from Google Maps** to the app.

## 4. Target specification (draft)

| Item | Target | Status |
|------|--------|--------|
| Display | Round, about 1.3–1.4", colour or mono | **TEST** (sunlight shoot-out) |
| Readability | Readable in direct sun, with polarised sunglasses and a tinted visor | **TEST** |
| Battery life | At least 14 h of riding (market reference from search summaries) | **TEST** |
| Charging | USB-C; optional 12 V bike cable | Decided |
| Water/dust | IP67 | **TEST** at a lab |
| Vibration | Survives motorcycle vibration (IEC 60068-2-64 style test) | **TEST** |
| Temperature | Works in sun-soaked and cold conditions (e.g. 0 to +60 °C) | **TEST**, number is a guess |
| Weight | About 70 g or less | **TEST** (Moto II reportedly 42 g plastic / 60 g metal) |
| Controls | 2–4 sealed, glove-friendly buttons | **TEST** with gloves |
| Mount | Handlebar clamp (common tube sizes) and mirror-stem option | **TEST** with real bikes |
| Phone | Android 7+ with Google Play services | Decided |
| Updates | Over-the-air firmware updates from the app | Required for production |
| Warranty | 1 year | Business decision |

## 5. Features

| v1 (must have) | Later |
|----------------|-------|
| Next turn arrow, distance, street name | iPhone app |
| Speed, ETA, remaining distance | Ride tracking and history |
| Share-from-Google-Maps destination | In-app search |
| Auto-reconnect; auto-dim | Crash alert, calls/messages, music control |
| Reroute and "Arrived" screens | Offline maps / own routing engine |
| OTA update | Voice prompts to helmet headset |

## 6. Price and cost worksheet

**Per device:** from our rough BOM estimate, about **US$35–100** landed cost at pilot volumes (display and
enclosure dominate).

Rule of thumb **(verify)**: retail price about **3× landed cost** to cover channel margin, warranty and marketing.

| Landed cost | 3× price | In rupees at ₹90/$ (assumption) |
|-------------|----------|---------------------------------|
| $35 | $105 | about ₹9,450 |
| $60 | $180 | about ₹16,200 |
| $100 | $300 | about ₹27,000 |

Compare with the **Beeline Moto II's current price in India** (check) and with what riders say in interviews.

**Google navigation cost (India):** first 7,000 destinations/month free, then about **$0.008 per destination**.

| Rider type | Destinations / month | Cost per user / month |
|------------|----------------------|------------------------|
| Commuter, 20 rides | 20 | $0.16 |
| Heavy user, 60 rides | 60 | $0.48 |
| **Delivery rider, 100 a day** | 3,000 | **$24** |

Over 3 years a 20-rides-a-month user costs about **$5.8** in Google fees, which a hardware price can absorb.
A delivery rider can **not**, so target commuters and tourers first, or add a subscription / own routing for that segment.

## 7. Validation gates (suggested thresholds, adjust to your risk appetite)

| Gate | Pass if |
|------|---------|
| Interviews | 20 riders done, notes logged |
| Interest | At least 10 of 20 say they would buy at your target price |
| Waitlist | At least 100 sign-ups from your target riders |
| Pre-orders | A meaningful number of **refundable deposits** (suggest 30+) |
| Competition | You can state clearly why they would choose you over Beeline or a phone mount |

If you miss the gates: change the product, the price or the segment before any tooling or inventory spend.

---

## 8. Rider interview script (15–20 min, listen more than you pitch)

Record the answers in the table at the bottom. Ask open questions; don't lead.

**A. Background**
1. What bike do you ride, and how often? Commute, tours or work?
2. How far is a typical ride? Do you ride in rain or at night?

**B. How they navigate today**
3. Walk me through your last unfamiliar ride. How did you navigate?
4. Where does the phone go (mount, pocket, tank bag)? What goes wrong?
5. Have you had a phone damaged, overheated, lost, or a screen unreadable in sun?
6. Do you use a helmet headset or voice prompts?

**C. Existing solutions**
7. Have you used or seen Beeline, a bike GPS (Garmin, TomTom) or a phone mount with a screen? What did you like or dislike?
8. What stopped you from buying one?

**D. Our idea** (show a photo or video of the prototype)
9. In your own words, what does this do? Would you use it?
10. What would make you trust it or not? (battery, waterproof, theft, phone battery drain)
11. Which matters most: screen brightness, battery life, size, price, ease of setup, looks?

**E. Price** (ask all four separately)
12. At what price is it so cheap you'd doubt its quality?
13. A bargain?
14. Getting expensive, but you'd still consider it?
15. Too expensive to consider?

**F. Buying**
16. Where would you buy it (Amazon, Flipkart, bike shop, brand website)?
17. Would you put down a refundable deposit now for early access? How much?
18. Who else rides with you and might want one?
19. Do you use an Android phone? Which model?
20. Anything I didn't ask that matters?

**Where to find riders:** local riding clubs, Royal Enfield / Himalayan and other owner groups, Team-BHP and
bike forums, Reddit r/IndianBikes, YouTube/Instagram rider communities, college biker clubs, delivery-rider groups.

**Tracking table (copy to a sheet):**

| # | Date | Segment | Bike | Phone | Current nav | Pain points | Price: too cheap / bargain / pricey / too much | Would deposit? | Notes |
|---|------|---------|------|-------|-------------|-------------|-----------------------------------------------|----------------|-------|

---

## 9. Landing page and waitlist copy (draft)

**Headline:** Your next turn, right on your handlebar.
**Subhead:** MotoNav is a small, weatherproof display that shows your Google Maps directions while your phone stays safe in your pocket.

**Three benefits**
- **Eyes on the road:** a clear arrow, distance and street name, readable at a glance.
- **Phone stays safe:** no more phone shaking on the handlebar or baking in the sun.
- **Simple:** share a place from Google Maps, tap Start, ride.

**How it works:** 1. Share your destination from Google Maps. 2. MotoNav connects over Bluetooth. 3. Follow the arrow.

**FAQ (draft):** Works with Android first, iPhone planned · Battery life and waterproofing: *state your measured results only* ·
Does it need mobile data? (the phone does) · Price and delivery date: *do not promise a date you haven't validated*.

**Waitlist form fields:** name, email or WhatsApp, city, bike model, main use (commute / tours / delivery), Android phone model,
price you'd expect to pay, "Would you pay a refundable deposit?".

**Privacy note (needed):** say what you collect, why, how long, and how to delete it (India's data-protection rules: verify).

**Pre-orders:** take **refundable** deposits through an Indian payment gateway; state the refund policy clearly; don't take
money you can't return if you fail the gates **(verify the legal and gateway requirements)**.

---

## 10. This week

1. Fill the **open TEST values** with your own opinions (these become your hypotheses).
2. Book **5 rider interviews** (friends of friends, club members), then 15 more.
3. Build the landing page with a waitlist.
4. Check the live **Beeline Moto II price** in India and note its reviews/complaints.
5. Prepare a photo and a 30-second video of the prototype showing the arrow on the display.
