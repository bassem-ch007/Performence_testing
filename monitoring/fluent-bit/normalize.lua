-- Adds a clean "container" field (without the leading "/") and makes sure
-- the label fields used by the Loki output always exist.
function normalize(tag, timestamp, record)
    local name = record["container_name"] or "unknown"
    record["container"] = string.gsub(name, "^/", "")
    if record["ARCHITECTURE"] == nil then
        record["ARCHITECTURE"] = "unknown"
    end
    if record["level"] == nil then
        record["level"] = "UNKNOWN"
    end
    return 1, timestamp, record
end
