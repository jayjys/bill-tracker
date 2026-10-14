(() => {
    const button = document.getElementById('copyAddress');
    const field = document.getElementById('forwardingAddress');
    if (!button || !field) return;
    button.addEventListener('click', async () => {
        try {
            await navigator.clipboard.writeText(field.value);
        } catch {
            field.select();
            document.execCommand('copy');
        }
        button.textContent = 'Copied';
        setTimeout(() => (button.textContent = 'Copy'), 1500);
    });
})();
