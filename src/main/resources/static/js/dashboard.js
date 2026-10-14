(async () => {
    const canvas = document.getElementById('trendChart');
    if (!canvas || !window.Chart) return;

    const res = await fetch('/api/trends?months=12', { headers: { Accept: 'application/json' } });
    if (!res.ok) return;
    const report = await res.json();

    const css = getComputedStyle(document.documentElement);
    const text = css.getPropertyValue('--muted').trim();
    const grid = css.getPropertyValue('--border').trim();
    const money = v => '$' + Number(v).toLocaleString(undefined, { maximumFractionDigits: 0 });
    const monthLabel = ym => {
        const [y, m] = ym.split('-').map(Number);
        return new Date(y, m - 1, 1).toLocaleDateString(undefined, { month: 'short', year: '2-digit' });
    };

    new Chart(canvas, {
        type: 'bar',
        data: {
            labels: report.months.map(monthLabel),
            datasets: report.series.map(s => ({
                label: s.label,
                data: s.values.map(Number),
                backgroundColor: s.color,
                borderRadius: 3,
                maxBarThickness: 36,
            })),
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            interaction: { mode: 'index', intersect: false },
            plugins: {
                legend: { position: 'bottom', labels: { color: text, boxWidth: 10, boxHeight: 10, usePointStyle: true } },
                tooltip: {
                    callbacks: {
                        label: ctx => ctx.raw ? `${ctx.dataset.label}: $${ctx.raw.toFixed(2)}` : null,
                        footer: items => 'Total: $' + items.reduce((sum, i) => sum + i.raw, 0).toFixed(2),
                    },
                },
            },
            scales: {
                x: { stacked: true, grid: { display: false }, ticks: { color: text } },
                y: { stacked: true, grid: { color: grid }, border: { display: false }, ticks: { color: text, callback: money } },
            },
        },
    });
})();
