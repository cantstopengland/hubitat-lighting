/**
 * Daily Lighting Timeline
 *
 * A single-app approach to daily lighting routines. Each timeslot fires at a
 * fixed time or at a sunrise/sunset offset and holds one rule per light:
 *   - power: turn on, turn off, or leave as is
 *   - level: set to, raise to at least, dim to at most, or no change
 * Level rules always pre-stage the light's next "on" level when the device
 * supports it.
 *
 * The timeline is edited in a web page served by this app (see mappings).
 * The page itself lives in the hub's File Manager as HTML_FILE.
 *
 * Author: cantstopengland
 * Date: 2025-10-24
 * Updated: Added pre-staging support via setDefaultDimmerLevel
 * Updated 2026-11-23: Also pre-stage P14 (remote default level) on Inovelli Red Series 800 (VZW31-SN)
 * Updated 2026-11-23: v2 - visual timeline page, per-light rules, on/off actions,
 *                     catch-up after hub restarts, pending levels for lights that can't pre-stage
 * Updated 2026-11-24: Schedules - several timelines, each used on chosen days of the week.
 *                     A day runs from 4 AM to 4 AM, so late-night timeslots belong to the evening before.
 */
import groovy.json.JsonOutput
import groovy.transform.Field

@Field static final String HTML_FILE = "daily-lighting-timeline.html"
@Field static final List LEVEL_MODES = ["set", "atLeast", "atMost"]
@Field static final List POWER_MODES = ["on", "off"]
@Field static final List TIMINGS = ["fixed", "sunrise", "sunset"]
// A schedule's day starts at 4 AM; earlier timeslots belong to the previous day's schedule.
@Field static final int DAY_START_MIN = 240
@Field static final List DAY_OPTIONS = [["1": "Monday"], ["2": "Tuesday"], ["3": "Wednesday"], ["4": "Thursday"],
                                        ["5": "Friday"], ["6": "Saturday"], ["7": "Sunday"]]
@Field static final List DAY_SHORT = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]

definition(
    name: "Daily Lighting Timeline",
    namespace: "cantstopengland.hubitat",
    author: "cantstopengland",
    description: "Manage lighting across a unified daily timeline with sunrise, sunset, and custom times.",
    category: "Lighting",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: true,
    oauth: true
)

preferences {
    page(name: "mainPage")
}

mappings {
    path("/ui")             { action: [GET: "apiPage"] }
    path("/timeline")       { action: [GET: "apiGetTimeline", POST: "apiSaveTimeline"] }
    path("/state")          { action: [GET: "apiGetState"] }
    path("/test/:slotId")   { action: [POST: "apiTestSlot"] }
}

// ---------------------------------------------------------------------------
// App page
// ---------------------------------------------------------------------------

def mainPage() {
    ensureMigrated()
    syncScheduleSettings()
    dynamicPage(name: "mainPage", title: "Daily Lighting Timeline", install: true, uninstall: true) {
        section("Timeline") {
            String url = timelineUrl()
            if (url) {
                href name: "openTimeline", title: "Open the timeline",
                     description: "Opens the visual editor in a new tab. Bookmark it; the link includes this app's access token.",
                     url: url, style: "external"
            } else {
                paragraph "<b>OAuth isn't enabled yet.</b> Go to <i>Apps Code → Daily Lighting Timeline → OAuth</i>, " +
                          "click <i>Enable OAuth in App</i>, then <i>Update</i>. Reopen this page afterwards."
            }
            paragraph summaryText()
        }
        section("Schedules") {
            paragraph "Each schedule is a separate timeline used on the days you pick. A day runs from 4 AM to 4 AM, " +
                      "so a 12:30 AM timeslot after Friday evening belongs to Friday's schedule. " +
                      "Add, copy, or delete schedules here or in the timeline page."
            schedules().each { Map sc ->
                int n = (sc.slots ?: []).size()
                paragraph "<b>${sc.name}</b> · ${n} timeslot${n == 1 ? '' : 's'} · ${daysText(sc.days)}"
                input "name_${sc.id}", "text", title: "Name", required: false, submitOnChange: true, width: 4
                input "days_${sc.id}", "enum", title: "Days", options: DAY_OPTIONS, multiple: true, required: false,
                      submitOnChange: true, width: 8
            }
            coverageWarnings().each { paragraph "<span style='color:#b45309'>⚠ ${it}</span>" }
            input "addScheduleBtn", "button", title: "Add a schedule (copy of ${schedules() ? schedules()[0].name : 'nothing'})"
        }
        section("Lights") {
            input "lights", "capability.switchLevel", title: "Dimmers this app can control",
                  description: "Every light selected here appears in the timeline, grouped by its Hubitat room.",
                  multiple: true, required: false, submitOnChange: true
        }
        section("Advanced", hideable: true, hidden: true) {
            paragraph "The visual editor is served from the File Manager file <code>${HTML_FILE}</code>."
            if (state.migratedAt) {
                paragraph "Imported ${state.migratedLegacyCount} legacy time slots into ${state.migratedSlotCount} timeslots on ${state.migratedAt}. " +
                          "The original settings are kept, so the previous app version still works if you roll back."
            }
            if (state.confirmRemigrate) {
                paragraph "<b>This replaces all schedules with one \"Every day\" schedule built from the legacy settings.</b>"
                input "remigrateConfirmBtn", "button", title: "Yes, replace the timeline"
                input "remigrateCancelBtn", "button", title: "Cancel"
            } else {
                input "remigrateBtn", "button", title: "Re-import from legacy settings"
            }
            input "debugLogging", "bool", title: "Debug logging", defaultValue: false, submitOnChange: true
        }
    }
}

void appButtonHandler(String btn) {
    switch (btn) {
        case "remigrateBtn":
            state.confirmRemigrate = true
            break
        case "remigrateCancelBtn":
            state.remove("confirmRemigrate")
            break
        case "remigrateConfirmBtn":
            state.remove("confirmRemigrate")
            state.timeline = wrapSlots(migrateLegacy().slots as List, (toInt(state.timeline?.version) ?: 0) + 1)
            pushScheduleSettings()
            rescheduleAll()
            break
        case "addScheduleBtn":
            Map tl = state.timeline
            Map src = (tl.schedules ?: [])[0] as Map
            List slots = (src?.slots ?: []).withIndex().collect { Map sl, int i ->
                sl + [id: newId(i), rules: (sl.rules ?: [:]).collectEntries { k, v -> [k, v + [:]] }]
            }
            tl.schedules << [id: newId(99), name: "New schedule", days: [], slots: slots]
            tl.version = (toInt(tl.version) ?: 0) + 1
            state.timeline = tl
            pushScheduleSettings()
            break
    }
}

private String timelineUrl() {
    if (!state.accessToken) {
        try {
            createAccessToken()
        } catch (Exception e) {
            return null
        }
    }
    return "${getFullLocalApiServerUrl()}/ui?access_token=${state.accessToken}"
}

private String summaryText() {
    int nSched = schedules().size()
    Map today = scheduleForDow(serviceDow(new Date()))
    String text = "${nSched} schedule${nSched == 1 ? '' : 's'}, ${(settings.lights ?: []).size()} lights selected. " +
                  "Today: <b>${today ? today.name : 'no schedule'}</b>."
    Map next = nextUpcoming()
    if (next) text += " Next: <b>${next.when.format('h:mm a', tz())}</b> ${next.slot.label ?: ''}" +
                      (nSched > 1 ? " (${next.sched.name})" : "")
    return text
}

/** Days that no schedule covers, and days claimed by more than one (the first listed wins). */
private List coverageWarnings() {
    List out = []
    List missing = []
    (1..7).each { int d ->
        List hits = schedules().findAll { normDays(it.days).contains(d) }
        if (!hits) missing << DAY_SHORT[d - 1]
        else if (hits.size() > 1) out << "${DAY_OPTIONS[d - 1][d.toString()]} is in ${hits*.name.join(' and ')}; ${hits[0].name} is used."
    }
    if (missing) out.add(0, "No schedule runs on ${missing.join(', ')}.")
    return out
}

private String daysText(def days) {
    List d = normDays(days)
    if (d.size() == 7) return "Every day"
    if (!d) return "No days"
    if (d == [1, 2, 3, 4, 5]) return "Mon–Fri"
    if (d == [6, 7]) return "Sat–Sun"
    return d.collect { DAY_SHORT[it - 1] }.join(", ")
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

def installed() {
    log.info "Daily Lighting Timeline installed."
    initialize()
}

def updated() {
    log.info "Daily Lighting Timeline updated."
    initialize()
}

// Accepts an optional event so subscriptions left by older versions still resolve.
def initialize(evt = null) {
    ensureMigrated()
    syncScheduleSettings()
    unschedule()
    unsubscribe()
    subscribe(location, "systemStart", "onSystemStart")
    if (settings.lights) subscribe(settings.lights, "switch.on", "onLightOn")
    schedule("0 58 23 ? * * *", "scheduleNextDayEvents")
    scheduleTodayEvents()
}

def onSystemStart(evt) {
    log.info "Hub restarted; rescheduling and checking for missed timeslots."
    initialize()
    runIn(30, "catchUp")
}

// ---------------------------------------------------------------------------
// Scheduling
// ---------------------------------------------------------------------------

def scheduleTodayEvents() {
    scheduleEventsForDate(new Date())
}

def scheduleNextDayEvents() {
    scheduleEventsForDate(new Date() + 1)
}

private void rescheduleAll() {
    unschedule("executeSlot")
    scheduleEventsForDate(new Date())
    // After the nightly 11:58 PM job has run, tomorrow's events must be scheduled too.
    Calendar cal = Calendar.getInstance(tz())
    if (cal.get(Calendar.HOUR_OF_DAY) == 23 && cal.get(Calendar.MINUTE) >= 58) {
        scheduleEventsForDate(new Date() + 1)
    }
}

private void scheduleEventsForDate(Date target) {
    Date nowDate = new Date()
    occurrences(target).each { Map o ->
        if (o.when.after(nowDate)) {
            logDebug "Scheduling [${o.slot.label}] from ${o.sched.name} for ${o.when}"
            runOnce(o.when, "executeSlot", [data: [slotId: o.slot.id], overwrite: false])
        }
    }
}

/**
 * Every timeslot that fires on the calendar date [d], each taken from the
 * schedule in charge of the day it belongs to (before 4 AM, the previous day).
 */
private List occurrences(Date d) {
    List out = []
    schedules().each { Map sc ->
        (sc.slots ?: []).each { Map slot ->
            if (!slot.rules) return
            Date when = slotDate(slot, d)
            if (when && scheduleForDow(serviceDow(when))?.id == sc.id) out << [slot: slot, sched: sc, when: when]
        }
    }
    return out
}

/** ISO day of week (1 = Monday) of the schedule day that [when] belongs to. */
private Integer serviceDow(Date when) {
    Calendar cal = Calendar.getInstance(tz())
    cal.setTime(when)
    if (cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE) < DAY_START_MIN) cal.add(Calendar.DATE, -1)
    return ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
}

private Map scheduleForDow(Integer dow) {
    return schedules().find { normDays(it.days).contains(dow) } as Map
}

private Date slotDate(Map slot, Date target) {
    TimeZone zone = tz()
    if (slot.timing == "fixed") {
        List parts = slot.time.toString().split(":").toList()
        Calendar cal = Calendar.getInstance(zone)
        cal.setTime(target)
        cal.set(Calendar.HOUR_OF_DAY, parts[0].toInteger())
        cal.set(Calendar.MINUTE, parts[1].toInteger())
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.getTime()
    }
    def sun = getSunriseAndSunset(date: target)
    Date base = slot.timing == "sunrise" ? sun?.sunrise : sun?.sunset
    if (!base) return null
    return new Date(base.time + (toInt(slot.offset) ?: 0) * 60000L)
}

private Map nextUpcoming() {
    Date nowDate = new Date()
    Map best = null
    [new Date(), new Date() + 1].each { Date d ->
        occurrences(d).each { Map o ->
            if (o.when.after(nowDate) && (!best || o.when.before(best.when))) best = o
        }
    }
    return best
}

/**
 * After a restart, replays level rules (never on/off) of every timeslot that
 * should have fired since the last one that did, oldest first. If nothing has
 * fired yet, only the most recent timeslot is replayed.
 */
def catchUp() {
    Date nowDate = new Date()
    Long last = state.lastFired?.at as Long
    List missed = []
    [new Date() - 1, new Date()].each { Date d ->
        occurrences(d).each { Map o ->
            if (!o.when.after(nowDate) && nowDate.time - o.when.time < 24L * 3600000L) missed << o
        }
    }
    missed.sort { it.when }
    if (last) {
        missed = missed.findAll { it.when.time > last }
    } else if (missed) {
        missed = [missed[-1]]
    }
    if (!missed) {
        log.info "Catch-up: no missed timeslots."
        return
    }
    missed.each { Map m ->
        log.info "Catch-up: applying levels from [${m.slot.label}] (was due ${m.when.format('h:mm a', tz())})"
        applySlot(m.slot, true)
    }
    state.lastFired = [slotId: missed[-1].slot.id, at: now()]
}

// ---------------------------------------------------------------------------
// Execution
// ---------------------------------------------------------------------------

def executeSlot(data) {
    ensureMigrated()
    String sid = data?.slotId?.toString()
    boolean isTest = data?.test == true
    Map slot = findSlot(sid)
    if (!slot && state.legacyIdMap?.get(sid)) {
        // A job scheduled by the previous app version, before the first save.
        slot = findSlot(state.legacyIdMap[sid].toString())
        if (slot && state.lastFired?.slotId == slot.id && now() - (state.lastFired.at as Long) < 120000L) {
            logDebug "Skipping duplicate legacy job ${sid}; ${slot.id} already ran."
            return
        }
    }
    if (!slot) {
        log.warn "Timeslot ${sid} no longer exists. Skipping."
        return
    }
    log.info "${isTest ? 'Testing' : 'Running'} timeslot [${slot.label}] (${slot.rules?.size() ?: 0} lights)"
    if (!isTest) state.lastFired = [slotId: slot.id, at: now()]
    applySlot(slot, false)
}

private void applySlot(Map slot, boolean levelsOnly) {
    Map byId = lightsById()
    (slot.rules ?: [:]).each { String devId, Map rule ->
        def light = byId[devId]
        if (!light) {
            logDebug "Light ${devId} is no longer selected. Skipping."
            return
        }
        try {
            applyRule(light, rule, levelsOnly)
        } catch (Exception e) {
            log.warn "Error applying [${slot.label}] to ${light.displayName}: ${e.message}"
        }
    }
}

/**
 * 1. Level rule: computes the live target (from the current level) and the
 *    staged target (from the pre-staged default, or current level if unknown),
 *    and pre-stages it. Lights that can't pre-stage and are off get a pending
 *    level that is applied the next time they turn on.
 * 2. Power: turn on (at the staged level), turn off, or leave as is (adjusting
 *    the live level if the light is on).
 */
private void applyRule(light, Map rule, boolean levelsOnly) {
    String devId = light.id.toString()
    String power = levelsOnly ? "" : (rule.power ?: "")
    String mode = rule.level ?: ""
    Integer value = mode ? clampLevel(toInt(rule.value)) : null

    boolean isOn = light.currentValue("switch") == "on"
    Integer current = toInt(light.currentValue("level")) ?: 0
    Integer staged = readDefaultLevel(light)
    boolean canStage = canPrestage(light)

    Integer liveTarget = mode ? adjustLevel(mode, current, value) : null
    Integer stageTarget = mode ? adjustLevel(mode, staged != null ? staged : current, value) : null

    logDebug "${light.displayName}: switch=${isOn ? 'on' : 'off'} level=${current} staged=${staged} rule=${rule} live->${liveTarget} stage->${stageTarget}"

    if (mode) {
        if (canStage) {
            if (staged == null || staged != stageTarget) prestage(light, stageTarget)
        } else if (power == "off" || (!isOn && power != "on")) {
            setPending(devId, stageTarget)
            log.info "${light.displayName} can't pre-stage; will set ${stageTarget}% the next time it turns on."
        }
    }

    switch (power) {
        case "on":
            clearPending(devId)
            if (mode) {
                Integer lvl = isOn ? liveTarget : stageTarget
                log.info "Turning on ${light.displayName} at ${lvl}%"
                light.setLevel(lvl)
            } else if (!isOn) {
                log.info "Turning on ${light.displayName}"
                light.on()
            }
            break
        case "off":
            if (isOn) {
                log.info "Turning off ${light.displayName}"
                light.off()
            }
            break
        default:
            if (isOn && mode) {
                clearPending(devId)
                if (liveTarget != current) {
                    log.info "Setting ${light.displayName} to ${liveTarget}%"
                    light.setLevel(liveTarget)
                }
            }
    }
}

private Integer adjustLevel(String mode, Integer base, Integer value) {
    Integer b = base ?: 0
    Integer result = value
    if (mode == "atLeast") result = Math.max(b, value)
    else if (mode == "atMost") result = Math.min(b, value)
    return clampLevel(result)
}

def onLightOn(evt) {
    String devId = evt.deviceId.toString()
    Integer lvl = toInt(state.pending?.get(devId))
    if (lvl == null) return
    clearPending(devId)
    log.info "${evt.displayName} turned on; applying pending level ${lvl}%"
    evt.device.setLevel(lvl)
}

private void setPending(String devId, Integer lvl) {
    Map p = (state.pending ?: [:]) as Map
    p[devId] = lvl
    state.pending = p
}

private void clearPending(String devId) {
    Map p = (state.pending ?: [:]) as Map
    if (p.containsKey(devId)) {
        p.remove(devId)
        state.pending = p
    }
}

private Integer readDefaultLevel(light) {
    for (String attr in ["defaultDimmerLevel", "defaultLevel", "presetLevel", "levelPreset"]) {
        Integer v = toInt(light.currentValue(attr))
        if (v != null && v > 0) return v
    }
    return null
}

private boolean canPrestage(light) {
    return light.hasCommand("setDefaultDimmerLevel") || light.hasCommand("setDefaultLevel") || light.hasCommand("presetLevel")
}

/**
 * Stages the next "on" level without turning the light on.
 *
 * Inovelli Red Series 800 (VZW31-SN) drivers map presetLevel() only to P13
 * (default level when turned on at the paddle). P14 (default level when the
 * hub turns it on) is written separately via setParameter(14, 1-99); on that
 * driver the value is a plain Z-Wave percent and 0 means "use previous level".
 * Blue Series (Zigbee) drivers already write both P13 and P14 in presetLevel().
 */
private void prestage(light, Integer sp) {
    log.info "Pre-staging ${light.displayName} to ${sp}%"
    if (light.hasCommand("setDefaultDimmerLevel")) light.setDefaultDimmerLevel(sp)
    else if (light.hasCommand("setDefaultLevel")) light.setDefaultLevel(sp)
    else if (light.hasCommand("presetLevel")) light.presetLevel(sp)

    if (isInovelliRed800(light) && light.hasCommand("setParameter")) {
        Integer remoteLevel = Math.min(Math.max(sp, 1), 99)
        log.info "Pre-staging ${light.displayName} remote default (P14) to ${remoteLevel}%"
        light.setParameter(14, remoteLevel)
    }
}

/**
 * True for Inovelli Red Series 800 dimmers (VZW31-SN), whose P14 is the
 * remote default level. Older Red Series (LZW31) use different parameter
 * numbers and are deliberately excluded.
 */
private boolean isInovelliRed800(light) {
    String typeName = (light.typeName ?: "").toLowerCase()
    if (!typeName.contains("inovelli") || typeName.contains("lzw")) return false
    return typeName.contains("vzw31") || typeName.contains("red series")
}

// ---------------------------------------------------------------------------
// HTTP API (local endpoints, access token required)
// ---------------------------------------------------------------------------

def apiPage() {
    String html
    try {
        byte[] bytes = downloadHubFile(HTML_FILE)
        html = new String(bytes, "UTF-8")
    } catch (Exception e) {
        html = "<!doctype html><meta charset='utf-8'><title>Lighting timeline</title>" +
               "<p style='font-family:sans-serif'>Couldn't load <code>${HTML_FILE}</code> from the hub's File Manager (${e.message}). " +
               "Upload it from the repository and reload this page.</p>"
    }
    render contentType: "text/html", data: html, status: 200
}

def apiGetTimeline() {
    ensureMigrated()
    renderJson(buildPayload(true))
}

def apiGetState() {
    renderJson(buildPayload(false))
}

def apiSaveTimeline() {
    Map body
    try {
        body = parseJson(request.body) as Map
    } catch (Exception e) {
        return renderJson([ok: false, error: "Invalid JSON"], 400)
    }
    Integer base = toInt(body?.baseVersion)
    Integer current = toInt(state.timeline?.version) ?: 0
    if (base != current) {
        return renderJson([ok: false, error: "conflict", version: current], 409)
    }
    Map clean = sanitizeTimeline(body.timeline, current + 1)
    if (clean.error) return renderJson([ok: false, error: clean.error], 400)
    state.timeline = clean
    pushScheduleSettings()
    rescheduleAll()
    logDebug "Saved timeline v${clean.version} (${clean.schedules.size()} schedules)"
    renderJson([ok: true, version: clean.version])
}

def apiTestSlot() {
    String sid = params.slotId
    if (!findSlot(sid)) return renderJson([ok: false, error: "Unknown timeslot"], 404)
    executeSlot([slotId: sid, test: true])
    renderJson([ok: true])
}

private def renderJson(Object obj, Integer status = 200) {
    return render(contentType: "application/json", data: JsonOutput.toJson(obj), status: status)
}

private Map buildPayload(boolean full) {
    TimeZone zone = tz()
    Map p = [
        nowHM: new Date().format("HH:mm", zone),
        lights: lightsPayload(),
        lastFired: state.lastFired
    ]
    if (full) {
        def sun = getSunriseAndSunset(date: new Date())
        Integer dow = serviceDow(new Date())
        p.timeline = state.timeline ?: [version: 0, schedules: []]
        p.dayStart = DAY_START_MIN
        p.serviceDow = dow
        p.todaySchedule = scheduleForDow(dow)?.id
        p.sun = [sunrise: sun?.sunrise?.format("HH:mm", zone), sunset: sun?.sunset?.format("HH:mm", zone)]
        p.hubName = location.name
    }
    return p
}

private List lightsPayload() {
    Map rooms = roomLookup()
    Map pending = (state.pending ?: [:]) as Map
    return (settings.lights ?: []).collect { d ->
        String id = d.id.toString()
        [
            id: id,
            name: d.displayName,
            room: rooms[id] ?: "Unassigned",
            sw: d.currentValue("switch"),
            level: toInt(d.currentValue("level")),
            staged: readDefaultLevel(d),
            canPrestage: canPrestage(d),
            pending: toInt(pending[id])
        ]
    }
}

private Map roomLookup() {
    Map m = [:]
    try {
        getRooms()?.each { r ->
            (r.deviceIds ?: []).each { m[it.toString()] = r.name }
        }
    } catch (Exception e) {
        logDebug "getRooms() unavailable: ${e.message}"
    }
    if (!m) {
        settings.lights?.each { d ->
            try {
                def rn = d.getRoomName()
                if (rn) m[d.id.toString()] = rn
            } catch (Exception ignored) { }
        }
    }
    return m
}

private Map sanitizeTimeline(def tl, int version) {
    if (!(tl instanceof Map) || !(tl.schedules instanceof List) || !tl.schedules) return [error: "Missing schedules"]
    Set allowed = (settings.lights ?: []).collect { it.id.toString() } as Set
    Set seenSlots = [] as Set
    Set seenScheds = [] as Set
    List out = []
    tl.schedules.each { sc ->
        if (!(sc instanceof Map)) return
        String id = (sc.id ?: "").toString()
        if (!(id ==~ /[A-Za-z0-9_-]{1,40}/) || seenScheds.contains(id)) return
        seenScheds << id
        String name = (sc.name ?: "").toString().trim().take(40) ?: "Schedule"
        out << [id: id, name: name, days: normDays(sc.days), slots: sanitizeSlots(sc.slots, allowed, seenSlots)]
    }
    if (!out) return [error: "Missing schedules"]
    return [version: version, schedules: out]
}

private List sanitizeSlots(def slots, Set allowed, Set seen) {
    List out = []
    if (!(slots instanceof List)) return out
    slots.each { s ->
        if (!(s instanceof Map)) return
        String id = (s.id ?: "").toString()
        if (!(id ==~ /[A-Za-z0-9_-]{1,40}/) || seen.contains(id)) return
        String timing = TIMINGS.contains(s.timing) ? s.timing : null
        if (!timing) return
        String time = null
        Integer offset = 0
        if (timing == "fixed") {
            time = s.time?.toString()
            if (!(time ==~ /([01]\d|2[0-3]):[0-5]\d/)) return
        } else {
            offset = Math.max(-720, Math.min(720, toInt(s.offset) ?: 0))
        }
        Map rules = [:]
        if (s.rules instanceof Map) {
            s.rules.each { k, r ->
                String dev = k.toString()
                if (!allowed.contains(dev) || !(r instanceof Map)) return
                String p = POWER_MODES.contains(r.power) ? r.power : ""
                String l = LEVEL_MODES.contains(r.level) ? r.level : ""
                if (!p && !l) return
                rules[dev] = [power: p, level: l, value: l ? clampLevel(toInt(r.value) ?: 50) : null]
            }
        }
        seen << id
        out << [id: id, label: (s.label ?: "").toString().take(60), timing: timing, time: time, offset: offset, rules: rules]
    }
    return out
}

private List normDays(def v) {
    if (v == null) return []
    List raw = v instanceof Collection ? v as List : [v]
    return raw.collect { toInt(it) }.findAll { it != null && it >= 1 && it <= 7 }.unique().sort()
}

// ---------------------------------------------------------------------------
// Schedules <-> app settings
//
// Each schedule's name and days are mirrored into settings (name_<id>,
// days_<id>) so they can be edited on the app page. The timeline in state is
// the source of truth; settings only win when they were edited on the app page.
// ---------------------------------------------------------------------------

private void syncScheduleSettings() {
    Map tl = state.timeline
    if (!tl?.schedules) return
    List synced = (state.syncedSchedules ?: []) as List
    boolean changed = false
    tl.schedules.each { Map sc ->
        if (!synced.contains(sc.id)) return
        List d = normDays(settings["days_${sc.id}"])
        if (d != normDays(sc.days)) { sc.days = d; changed = true }
        String n = (settings["name_${sc.id}"] ?: "").toString().trim().take(40)
        if (n && n != sc.name) { sc.name = n; changed = true }
    }
    if (changed) {
        tl.version = (toInt(tl.version) ?: 0) + 1
        state.timeline = tl
        log.info "Schedule names or days changed on the app page."
        rescheduleAll()
    }
    pushScheduleSettings()
}

private void pushScheduleSettings() {
    List ids = []
    schedules().each { Map sc ->
        List d = normDays(sc.days)
        if (d) app.updateSetting("days_${sc.id}", [type: "enum", value: d.collect { it.toString() }])
        else app.removeSetting("days_${sc.id}")
        app.updateSetting("name_${sc.id}", [type: "text", value: sc.name])
        ids << sc.id
    }
    ((state.syncedSchedules ?: []) as List).findAll { !ids.contains(it) }.each {
        app.removeSetting("days_${it}")
        app.removeSetting("name_${it}")
    }
    state.syncedSchedules = ids
}

/** Unique enough within this app: the current time plus an index that differs per call site. */
private String newId(int index) {
    return "s" + Long.toString(now(), 36) + "_" + index
}

// ---------------------------------------------------------------------------
// Migration from v1 settings (lights_/setpoint_/applyTo_ per slot)
// ---------------------------------------------------------------------------

private void ensureMigrated() {
    if (state.timeline == null) {
        Map legacy = migrateLegacy()
        state.timeline = wrapSlots(legacy.slots as List, 1)
    } else if (state.timeline.schedules == null) {
        // Single-timeline v2 data becomes one schedule used every day.
        state.timeline = wrapSlots((state.timeline.slots ?: []) as List, (toInt(state.timeline.version) ?: 0) + 1)
        log.info "Moved the timeline into an \"Every day\" schedule."
    }
}

private Map wrapSlots(List slots, Integer version) {
    return [version: version, schedules: [[id: "d1", name: "Every day", days: [1, 2, 3, 4, 5, 6, 7], slots: slots]]]
}

private Map migrateLegacy() {
    List legacyIds = []
    if (settings["lights_sunrise"]) legacyIds << "sunrise"
    if (settings["lights_sunset"]) legacyIds << "sunset"
    legacyIds.addAll((state.customSlots ?: []).collect { it.toString() })

    Map bySig = [:]
    Map legacyMap = [:]
    Set deviceIds = [] as Set
    int n = 0
    int legacyCount = 0

    legacyIds.each { String lid ->
        def lights = settings["lights_${lid}"]
        Integer sp = toInt(settings["setpoint_${lid}"])
        if (!lights || sp == null) return

        String timing
        String time = null
        Integer offset = 0
        if (lid == "sunrise" || lid == "sunset") {
            timing = lid
        } else if (lid.startsWith("custom_")) {
            timing = "fixed"
            time = legacyFixedTime(lid)
            if (!time) return
        } else if (lid.startsWith("sunrise_offset_") || lid.startsWith("sunset_offset_")) {
            timing = lid.startsWith("sunrise") ? "sunrise" : "sunset"
            offset = settings["editOffset_${lid}"] != null ? toInt(settings["editOffset_${lid}"]) : toInt(lid.split("_")[2])
        } else {
            return
        }

        String sig = "${timing}|${time}|${offset}"
        Map slot = bySig[sig]
        if (!slot) {
            slot = [id: "m${++n}", label: "", timing: timing, time: time, offset: offset, rules: [:]]
            bySig[sig] = slot
        }
        String lbl = settings["editLabel_${lid}"]?.toString()
        if (!lbl && (lid == "sunrise" || lid == "sunset")) lbl = lid.capitalize()
        if (lbl && !slot.label.toString().split(" / ").contains(lbl)) {
            slot.label = slot.label ? "${slot.label} / ${lbl}".toString() : lbl
        }
        String mode = legacyMode(settings["applyTo_${lid}"]?.toString())
        (lights instanceof List ? lights : [lights]).each { d ->
            String dev = d.id.toString()
            if (slot.rules[dev]) log.warn "Migration: ${d.displayName} appears twice at ${sig}; keeping the later rule."
            slot.rules[dev] = [power: "", level: mode, value: clampLevel(sp)]
            deviceIds << dev
        }
        legacyMap[lid] = slot.id
        legacyCount++
    }

    if (!settings.lights && deviceIds) {
        app.updateSetting("lights", [type: "capability.switchLevel", value: deviceIds as List])
    }
    state.legacyIdMap = legacyMap
    state.migratedAt = new Date().format("yyyy-MM-dd HH:mm", tz())
    state.migratedLegacyCount = legacyCount
    state.migratedSlotCount = bySig.size()
    log.info "Migrated ${legacyCount} legacy time slots into ${bySig.size()} timeslots."
    return [version: 1, slots: bySig.values() as List]
}

private String legacyFixedTime(String lid) {
    def setting = settings["editTime_${lid}"]
    if (setting) {
        try {
            return timeToday(setting.toString(), tz()).format("HH:mm", tz())
        } catch (Exception e) {
            if (setting.toString() ==~ /\d{2}:\d{2}/) return setting.toString()
        }
    }
    List parts = lid.split("_").toList()
    if (parts.size() >= 2 && parts[1] ==~ /\d{4}/) return "${parts[1].substring(0, 2)}:${parts[1].substring(2, 4)}"
    return null
}

private String legacyMode(String applyTo) {
    switch (applyTo) {
        case "Only Lights Brighter than the Setpoint": return "atMost"
        case "Only Lights Dimmer than the Setpoint": return "atLeast"
        default: return "set"
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private List schedules() {
    return (state.timeline?.schedules ?: []) as List
}

private Map findSlot(String id) {
    for (Map sc in schedules()) {
        Map s = (sc.slots ?: []).find { it.id == id } as Map
        if (s) return s
    }
    return null
}

private Map lightsById() {
    Map m = [:]
    settings.lights?.each { m[it.id.toString()] = it }
    return m
}

private TimeZone tz() {
    return location.timeZone ?: TimeZone.getDefault()
}

private Integer clampLevel(Integer v) {
    if (v == null) return null
    return Math.max(1, Math.min(100, v))
}

/**
 * Safely coerces a value (String, BigDecimal, Integer, or null, possibly
 * "50.0") into an Integer. Returns null on failure.
 */
private Integer toInt(value) {
    if (value == null || value == "") return null
    try {
        return (value as BigDecimal).intValue()
    } catch (Exception e) {
        return null
    }
}

private void logDebug(String msg) {
    if (settings.debugLogging) log.debug msg
}
