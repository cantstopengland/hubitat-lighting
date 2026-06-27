/**
 * Daily Lighting Timeline
 *
 * A single-app approach to daily lighting routines. Presents a chronologically
 * sorted timeline of dynamic triggers like sunrise, sunset, and custom times.
 *
 * Author: cantstopengland
 * Date: 2025-10-24
 * Updated: Added pre-staging support via setDefaultDimmerLevel
 */
definition(
    name: "Daily Lighting Timeline",
    namespace: "cantstopengland.hubitat",
    author: "cantstopengland",
    description: "Manage lighting levels across a unified daily timeline with sunrise, sunset, and custom times.",
    category: "Lighting",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: true // Usually, only one master timeline is needed
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Daily Lighting Timeline", install: true, uninstall: true) {
        
        section("Instructions") {
            paragraph "Click on any time slot below to expand it and configure your lighting rules. The list is automatically sorted based on today's local Sunrise and Sunset times. You can add more specific or offset times at the bottom."
        }
        
        section("Filter Timeline", hideable: true, hidden: false) {
            input "filterLight", "capability.switchLevel", title: "Filter by a specific light", multiple: false, required: false, submitOnChange: true
            if (settings.filterLight) {
                paragraph "<b>Filtering active:</b> Only showing time slots that include <i>${settings.filterLight.displayName}</i>."
                input "clearFilterBtn", "button", title: "Clear Filter"
            }
        }
        
        // Fetch the chronologically sorted slots based on today's sun data
        def slots = getSortedSlots(new Date())
        
        slots.each { slot ->
            // Determine if the user has configured anything for this specific slot
            def isConfigured = settings["lights_${slot.id}"] != null
            def lights = settings["lights_${slot.id}"]
            
            // --- FILTERING LOGIC ---
            boolean showSlot = true
            if (settings.filterLight) {
                if (!isConfigured || !lights) {
                    showSlot = false // Hide unconfigured slots when filtering
                } else {
                    def filteredLightId = settings.filterLight.id
                    def lightList = (lights instanceof List) ? lights : [lights]
                    showSlot = lightList.any { it?.id == filteredLightId }
                }
            }
            
            if (showSlot) {
                def sectionTitle = "⚪ ${slot.label}"
                
                if (isConfigured) {
                    def numLights = (lights instanceof List) ? lights.size() : 1
                    def setpoint = settings["setpoint_${slot.id}"]
                    
                    if (setpoint != null) {
                        def lightWord = numLights == 1 ? "light" : "lights"
                        sectionTitle = "🟢 ${slot.label} (${numLights} ${lightWord} - ${setpoint}%)"
                    } else {
                        sectionTitle = "🟡 ${slot.label} [Needs Configuration]"
                    }
                }
                
                // Render the section. All sections collapsed by default for a clean UI.
                section(sectionTitle, hideable: true, hidden: true) {
                    
                    // Allow editing of custom labels, times, and offsets
                    if (slot.type.startsWith("custom")) {
                        input "editLabel_${slot.id}", "text", title: "Label", required: false, submitOnChange: true
                        
                        if (slot.type == "custom_time") {
                            def legacyTimeStr = null
                            if (!settings["editTime_${slot.id}"]) {
                                def tz = location.timeZone ?: TimeZone.getDefault()
                                Calendar cal = Calendar.getInstance(tz)
                                cal.setTime(new Date())
                                cal.set(Calendar.HOUR_OF_DAY, slot.h)
                                cal.set(Calendar.MINUTE, slot.m)
                                legacyTimeStr = cal.getTime().format("yyyy-MM-dd'T'HH:mm:ss.SSSZ", tz)
                            }
                            input "editTime_${slot.id}", "time", title: "Time", required: true, defaultValue: legacyTimeStr, submitOnChange: true
                        } else if (slot.type == "custom_sunrise" || slot.type == "custom_sunset") {
                            input "editOffset_${slot.id}", "number", title: "Offset (minutes)", range: "-1440..1440", required: true, defaultValue: slot.offset, submitOnChange: true
                        }
                    }
                    
                    input "lights_${slot.id}", "capability.switchLevel", title: "Select Dimmable Lights", multiple: true, required: false, submitOnChange: true
                    
                    // Only show detailed options if lights are selected
                    if (settings["lights_${slot.id}"]) {
                        input "setpoint_${slot.id}", "number", title: "Set Level To (%)", range: "1..100", required: true
                        input "applyTo_${slot.id}", "enum", title: "Apply to...", options: [
                            "All Selected Lights",
                            "Only Lights Brighter than the Setpoint",
                            "Only Lights Dimmer than the Setpoint",
                            "Only Lights currently on",
                            "Only Lights currently off"
                        ], required: true, defaultValue: "All Selected Lights", submitOnChange: true
                    }
                    
                    // Show Test Configuration button if fully configured
                    if (settings["lights_${slot.id}"] && settings["setpoint_${slot.id}"] != null) {
                        input "testBtn_${slot.id}", "button", title: "Test This Configuration"
                    }
                    
                    if (slot.type.startsWith("custom")) {
                        input "remove_${slot.id}", "button", title: "Remove This Custom Time"
                    }
                }
            } // End of if(showSlot)
        }
        
        section("Add Custom Time Slot") {
            input "customSlotType", "enum", title: "Type of Custom Slot", options: ["Specific Time", "Sunrise Offset", "Sunset Offset"], required: false, submitOnChange: true
            
            if (settings.customSlotType) {
                // 1. Show the appropriate time/offset input
                if (settings.customSlotType == "Specific Time") {
                    input "newCustomTime", "time", title: "Select an exact time to add", required: false, submitOnChange: true
                } else if (settings.customSlotType == "Sunrise Offset" || settings.customSlotType == "Sunset Offset") {
                    input "newCustomOffset", "number", title: "Offset in minutes (- for before, + for after)", range: "-1440..1440", required: false, submitOnChange: true
                }
                
                // 2. Show the optional label input
                input "newCustomLabel", "text", title: "Optional Label (e.g., 'Bedtime')", required: false, submitOnChange: true
                
                // 3. Show the add button ONLY if the required time/offset is populated
                if ((settings.customSlotType == "Specific Time" && settings.newCustomTime) || 
                    ((settings.customSlotType == "Sunrise Offset" || settings.customSlotType == "Sunset Offset") && settings.newCustomOffset != null)) {
                    input "addCustomBtn", "button", title: "Add Time Slot"
                }
            }
        }
    }
}

void appButtonHandler(String btn) {
    if (btn == "clearFilterBtn") {
        app.removeSetting("filterLight")
    } else if (btn == "addCustomBtn") {
        def customId = null
        def uniqueSuffix = now() // Adds a unique timestamp to allow multiple slots at the exact same time
        
        if (settings.customSlotType == "Specific Time" && settings.newCustomTime) {
            def tz = location.timeZone ?: TimeZone.getDefault()
            def parsedDate = timeToday(settings.newCustomTime, tz)
            Calendar cal = Calendar.getInstance(tz)
            cal.setTime(parsedDate)
            
            def hh = String.format("%02d", cal.get(Calendar.HOUR_OF_DAY))
            def mm = String.format("%02d", cal.get(Calendar.MINUTE))
            customId = "custom_${hh}${mm}_${uniqueSuffix}"
            
            app.updateSetting("editTime_${customId}", [value: settings.newCustomTime, type: "time"])
        } else if (settings.customSlotType == "Sunrise Offset" && settings.newCustomOffset != null) {
            customId = "sunrise_offset_${settings.newCustomOffset}_${uniqueSuffix}"
            app.updateSetting("editOffset_${customId}", [value: settings.newCustomOffset, type: "number"])
        } else if (settings.customSlotType == "Sunset Offset" && settings.newCustomOffset != null) {
            customId = "sunset_offset_${settings.newCustomOffset}_${uniqueSuffix}"
            app.updateSetting("editOffset_${customId}", [value: settings.newCustomOffset, type: "number"])
        }
        
        if (customId) {
            def list = state.customSlots ?: []
            if (!list.contains(customId)) {
                list << customId
            }
            state.customSlots = list
            
            if (settings.newCustomLabel) {
                app.updateSetting("editLabel_${customId}", [value: settings.newCustomLabel, type: "text"])
            }
            
            app.removeSetting("newCustomTime")
            app.removeSetting("newCustomOffset")
            app.removeSetting("customSlotType")
            app.removeSetting("newCustomLabel")
        }
    } else if (btn.startsWith("remove_")) {
        def customId = btn.replace("remove_", "")
        if (state.customSlots) {
            def list = state.customSlots
            list.remove(customId)
            state.customSlots = list
        }
        app.removeSetting("lights_${customId}")
        app.removeSetting("setpoint_${customId}")
        app.removeSetting("applyTo_${customId}")
        app.removeSetting("editLabel_${customId}")
        app.removeSetting("editTime_${customId}")
        app.removeSetting("editOffset_${customId}")
    } else if (btn.startsWith("testBtn_")) {
        def slotId = btn.replace("testBtn_", "")
        log.info "Manual test triggered for slot ID: ${slotId}"
        executeSlot([slotId: slotId])
    }
}

def installed() {
    log.info "Daily Lighting Timeline Installed."
    initialize()
}

def updated() {
    log.info "Daily Lighting Timeline Updated."
    initialize()
}

def initialize() {
    unschedule()
    
    // Reschedule on hub reboot (Fixed to use string reference)
    subscribe(location, "systemStart", "initialize") 
    
    // Schedule a daily routine at 11:58 PM to load tomorrow's exact events (Fixed to use string reference)
    schedule("0 58 23 ? * * *", "scheduleNextDayEvents")
    
    // Run the scheduler immediately for whatever time is remaining today
    scheduleTodayEvents()
}

/**
 * Schedules all configured events that are still in the future for today.
 */
def scheduleTodayEvents() {
    log.info "Calculating and scheduling remaining events for Today."
    scheduleEventsForDate(new Date())
}

/**
 * Schedules all configured events for tomorrow (used by the 11:58 PM cron).
 */
def scheduleNextDayEvents() {
    log.info "Calculating and scheduling all events for Tomorrow."
    scheduleEventsForDate(new Date() + 1)
}

/**
 * Core scheduling engine. Looks at every configured slot, calculates its exact time 
 * on the target date, and schedules a runOnce task.
 */
def scheduleEventsForDate(Date targetDate) {
    def slots = getSortedSlots(targetDate)
    def now = new Date()
    
    slots.each { slot ->
        def configuredLights = settings["lights_${slot.id}"]
        def setpoint = settings["setpoint_${slot.id}"]
        
        if (configuredLights && setpoint != null) {
            Date slotTime = getSlotTimeForDate(slot, targetDate)
            
            if (slotTime && slotTime.after(now)) {
                log.info "Scheduling rule [${slot.label}] for: ${slotTime}"
                // runOnce default is to overwrite existing schedules for the same handler method.
                // We MUST set overwrite: false so multiple events can be scheduled correctly.
                runOnce(slotTime, "executeSlot", [data: [slotId: slot.id], overwrite: false])
            } else {
                log.debug "Skipping rule [${slot.label}], time is in the past for this scheduling window."
            }
        }
    }
}

/**
 * The execution block. Applies the lighting logic when a scheduled time arrives.
 */
def executeSlot(data) {
    def slotId = data.slotId
    log.info "Executing lighting sequence for slot ID: ${slotId}"
    
    def targetLights = settings["lights_${slotId}"]
    def setpoint = settings["setpoint_${slotId}"]
    def applyTo = settings["applyTo_${slotId}"]
    
    if (!targetLights || setpoint == null) {
        log.warn "Missing or incomplete configuration for ${slotId}. Skipping."
        return
    }
    
    Integer sp = toInt(setpoint)
    if (sp == null) {
        log.warn "Setpoint for ${slotId} is not a valid number (${setpoint}). Skipping."
        return
    }
    
    targetLights.each { light ->
      try {
        def currentSwitch = light.currentValue("switch")
        Integer currentLevel = toInt(light.currentValue("level")) ?: 0
        
        // Attempt to fetch the default level from known common readable attributes
        def rawDefault = light.currentValue("defaultDimmerLevel")
        if (rawDefault == null) rawDefault = light.currentValue("defaultLevel")
        if (rawDefault == null) rawDefault = light.currentValue("presetLevel")
        if (rawDefault == null) rawDefault = light.currentValue("levelPreset")
        
        // Fallback to current level if none of the attributes exist (or can't be parsed)
        Integer parsedDefault = toInt(rawDefault)
        Integer defaultLevel = (parsedDefault != null) ? parsedDefault : currentLevel

        log.debug "Evaluating: ${light.displayName} (Switch: ${currentSwitch}, Level: ${currentLevel}, Default: ${defaultLevel} [Raw attr: ${rawDefault}])"

        boolean hasSetDefaultDimmerLevel = light.hasCommand("setDefaultDimmerLevel")
        boolean hasSetDefaultLevel = light.hasCommand("setDefaultLevel")
        boolean hasPresetLevel = light.hasCommand("presetLevel")
        boolean supportsPrestaging = hasSetDefaultDimmerLevel || hasSetDefaultLevel || hasPresetLevel

        boolean shouldUpdateLevel = false
        boolean shouldUpdateDefault = false

        switch (applyTo) {
            case "All Selected Lights":
                shouldUpdateLevel = (currentSwitch == "on")
                shouldUpdateDefault = true
                break
            case "Only Lights Brighter than the Setpoint":
                shouldUpdateLevel = (currentSwitch == "on") && (currentLevel > sp)
                shouldUpdateDefault = (defaultLevel > sp) || shouldUpdateLevel
                break
            case "Only Lights Dimmer than the Setpoint":
                shouldUpdateLevel = (currentSwitch == "on") && (currentLevel < sp)
                shouldUpdateDefault = (defaultLevel < sp) || shouldUpdateLevel
                break
            case "Only Lights currently on":
                shouldUpdateLevel = (currentSwitch == "on")
                shouldUpdateDefault = (currentSwitch == "on")
                break
            case "Only Lights currently off":
                shouldUpdateLevel = false
                shouldUpdateDefault = (currentSwitch == "off")
                break
        }

        if (shouldUpdateLevel && shouldUpdateDefault && supportsPrestaging) {
            log.info "Setting ${light.displayName} level to ${sp}% AND pre-staging to ${sp}%"
            light.setLevel(sp)
            if (hasSetDefaultDimmerLevel) light.setDefaultDimmerLevel(sp)
            else if (hasSetDefaultLevel) light.setDefaultLevel(sp)
            else if (hasPresetLevel) light.presetLevel(sp)
        } else if (shouldUpdateLevel) {
            log.info "Setting ${light.displayName} level to ${sp}%"
            light.setLevel(sp)
        } else if (shouldUpdateDefault) {
            if (supportsPrestaging) {
                log.info "Pre-staging ${light.displayName} to ${sp}%"
                if (hasSetDefaultDimmerLevel) light.setDefaultDimmerLevel(sp)
                else if (hasSetDefaultLevel) light.setDefaultLevel(sp)
                else if (hasPresetLevel) light.presetLevel(sp)
            } else {
                log.info "Device ${light.displayName} lacks pre-staging commands. Falling back to setLevel(${sp}%)."
                light.setLevel(sp)
            }
        } else {
            log.debug "Skipping ${light.displayName}, conditions not met."
        }
      } catch (Exception e) {
        log.warn "Error processing ${light?.displayName}: ${e.message}. Continuing with remaining lights."
      }
    }
}

/**
 * Safely coerces a value (which may be a String, BigDecimal, Integer, or null,
 * possibly containing a decimal like "50.0") into an Integer. Returns null on failure.
 */
private Integer toInt(value) {
    if (value == null) return null
    try {
        return (value as BigDecimal).intValue()
    } catch (Exception e) {
        log.debug "Could not parse '${value}' as an integer: ${e.message}"
        return null
    }
}

/**
 * Generates and sorts the timeline for a specific date.
 */
private List getSortedSlots(Date targetDate) {
    def slots = []
    def tz = location.timeZone ?: TimeZone.getDefault()
    
    // 1. Fetch sunrise and sunset for the target date
    def sunInfo = getSunriseAndSunset(date: targetDate)
    
    if (sunInfo?.sunrise) {
        def timeStr = sunInfo.sunrise.format("HH:mm", tz)
        def parts = timeStr.split(":")
        def sortVal = parts[0].toInteger() * 60 + parts[1].toInteger()
        def label = "Sunrise (${sunInfo.sunrise.format("h:mm a", tz)})"
        def sp = settings["setpoint_sunrise"] != null ? settings["setpoint_sunrise"].toInteger() : 101
        
        slots << [id: "sunrise", type: "sunrise", label: label, sortValue: sortVal, setpointValue: sp]
    }
    
    if (sunInfo?.sunset) {
        def timeStr = sunInfo.sunset.format("HH:mm", tz)
        def parts = timeStr.split(":")
        def sortVal = parts[0].toInteger() * 60 + parts[1].toInteger()
        def label = "Sunset (${sunInfo.sunset.format("h:mm a", tz)})"
        def sp = settings["setpoint_sunset"] != null ? settings["setpoint_sunset"].toInteger() : 101
        
        slots << [id: "sunset", type: "sunset", label: label, sortValue: sortVal, setpointValue: sp]
    }
    
    // 2. Add custom user-defined slots
    if (state.customSlots) {
        state.customSlots.each { customId ->
            def sp = settings["setpoint_${customId}"] != null ? settings["setpoint_${customId}"].toInteger() : 101
            
            if (customId.startsWith("custom_")) {
                def parts = customId.split("_")
                if (parts.size() >= 2) {
                    def h, m
                    if (settings["editTime_${customId}"]) {
                        def parsedDate = timeToday(settings["editTime_${customId}"], tz)
                        def cal = Calendar.getInstance(tz)
                        cal.setTime(parsedDate)
                        h = cal.get(Calendar.HOUR_OF_DAY)
                        m = cal.get(Calendar.MINUTE)
                    } else {
                        def hhStr = parts[1].substring(0, 2)
                        def mmStr = parts[1].substring(2, 4)
                        h = hhStr.toInteger()
                        m = mmStr.toInteger()
                    }
                    
                    def isPM = h >= 12
                    def displayH = h % 12
                    if (displayH == 0) displayH = 12
                    def amPm = isPM ? "PM" : "AM"
                    def mmStrFormatted = String.format("%02d", m)
                    
                    def customName = settings["editLabel_${customId}"]
                    def labelSuffix = customName ? " - ${customName}" : " (Custom)"
                    def label = "${displayH}:${mmStrFormatted} ${amPm}${labelSuffix}"
                    
                    slots << [
                        id: customId, 
                        type: "custom_time", 
                        label: label, 
                        sortValue: h * 60 + m, 
                        h: h, 
                        m: m,
                        setpointValue: sp
                    ]
                }
            } else if (customId.startsWith("sunrise_offset_") || customId.startsWith("sunset_offset_")) {
                def isSunrise = customId.startsWith("sunrise")
                def parts = customId.split("_")
                
                def offset = settings["editOffset_${customId}"] != null ? settings["editOffset_${customId}"].toInteger() : parts[2].toInteger()
                def baseTime = isSunrise ? sunInfo?.sunrise : sunInfo?.sunset
                
                if (baseTime) {
                    def cal = Calendar.getInstance(tz)
                    cal.setTime(baseTime)
                    cal.add(Calendar.MINUTE, offset)
                    def slotTime = cal.getTime()
                    
                    def h = cal.get(Calendar.HOUR_OF_DAY)
                    def m = cal.get(Calendar.MINUTE)
                    def sortVal = h * 60 + m
                    
                    def offsetLabel = offset == 0 ? "" : (offset > 0 ? "+${offset}m" : "${offset}m")
                    def eventName = isSunrise ? "Sunrise" : "Sunset"
                    
                    def customName = settings["editLabel_${customId}"]
                    def labelSuffix = customName ? " - ${customName}" : ""
                    def label = "${eventName} ${offsetLabel} (${slotTime.format("h:mm a", tz)})${labelSuffix}"
                    
                    slots << [
                        id: customId,
                        type: isSunrise ? "custom_sunrise" : "custom_sunset",
                        label: label,
                        sortValue: sortVal,
                        offset: offset,
                        setpointValue: sp
                    ]
                }
            }
        }
    }
    
    // 3. Sort chronologically by 'Minute of the day', then by 'Setpoint' (Ascending)
    return slots.sort { a, b ->
        a.sortValue <=> b.sortValue ?: a.setpointValue <=> b.setpointValue ?: a.id <=> b.id
    }
}

/**
 * Calculates the exact java.util.Date object for a specific timeline slot on a specific day.
 */
private Date getSlotTimeForDate(slot, Date targetDate) {
    def tz = location.timeZone ?: TimeZone.getDefault()
    
    if (slot.type == "custom_time") {
        Calendar cal = Calendar.getInstance(tz)
        cal.setTime(targetDate)
        cal.set(Calendar.HOUR_OF_DAY, slot.h)
        cal.set(Calendar.MINUTE, slot.m)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.getTime()
    } else if (slot.type == "sunrise" || slot.type == "sunset") {
        def sunInfo = getSunriseAndSunset(date: targetDate)
        return slot.type == "sunrise" ? sunInfo?.sunrise : sunInfo?.sunset
    } else if (slot.type == "custom_sunrise" || slot.type == "custom_sunset") {
        def sunInfo = getSunriseAndSunset(date: targetDate)
        def baseTime = slot.type == "custom_sunrise" ? sunInfo?.sunrise : sunInfo?.sunset
        if (baseTime) {
            Calendar cal = Calendar.getInstance(tz)
            cal.setTime(baseTime)
            cal.add(Calendar.MINUTE, slot.offset ?: 0)
            return cal.getTime()
        }
    }
    return null
}