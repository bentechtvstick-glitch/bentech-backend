const BASE_URL =
  process.env.GOLDENOTT_BASE_URL || "https://goldenott.net";

const API_KEY = process.env.GOLDENOTT_API_KEY || "";

async function goldenRequest(path, options = {}) {
  if (!API_KEY) {
    throw new Error("GoldenOTT API key is not configured");
  }

  const response = await fetch(`${BASE_URL}/api/v1${path}`, {
    ...options,
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      "X-API-Key": API_KEY,
      ...(options.headers || {}),
    },
  });

  const text = await response.text();

  let data;
  try {
    data = JSON.parse(text);
  } catch {
    throw new Error(
      `GoldenOTT returned invalid JSON (${response.status})`
    );
  }

  if (!response.ok) {
    const message =
      data?.message ||
      data?.error ||
      `GoldenOTT HTTP ${response.status}`;

    const error = new Error(message);
    error.status = response.status;
    error.data = data;
    throw error;
  }

  return data;
}

export async function getPackages({ isPaidTrial = null } = {}) {
  const params = new URLSearchParams({
    per_page: "100",
    page: "1",
  });

  if (isPaidTrial !== null) {
    params.set("is_paid_trial", String(isPaidTrial));
  }

  return goldenRequest(`/packages?${params.toString()}`);
}

export async function createActiveCode({
  package_id,
  template_id,
  is_adult = false,
  notes = "",
}) {
  return goldenRequest("/active-codes", {
    method: "POST",
    body: JSON.stringify({
      package_id: Number(package_id),
      template_id: Number(template_id),
      is_adult: Boolean(is_adult),
      notes: String(notes || ""),
    }),
  });
}

export async function getActiveCode(codeId) {
  return goldenRequest(
    `/active-codes/${encodeURIComponent(codeId)}`
  );
}

export async function getActivatedCodes() {
  return goldenRequest("/active-codes/activated");
}

export async function getInactiveCodes() {
  return goldenRequest("/active-codes?per_page=100&page=1");
}

export async function extendActiveCode(codeId, package_id) {
  return goldenRequest(
    `/active-codes/${encodeURIComponent(codeId)}/extend`,
    {
      method: "POST",
      body: JSON.stringify({
        package_id: Number(package_id),
      }),
    }
  );
}

export async function refundActiveCode(codeId) {
  return goldenRequest(
    `/active-codes/${encodeURIComponent(codeId)}/refund`,
    {
      method: "POST",
    }
  );
}
