(() => {
    const zone = document.getElementById('dropzone');
    const input = document.getElementById('files');
    if (!zone || !input) return;
    ['dragenter', 'dragover'].forEach(evt => zone.addEventListener(evt, e => {
        e.preventDefault();
        zone.classList.add('dragover');
    }));
    ['dragleave', 'drop'].forEach(evt => zone.addEventListener(evt, e => {
        e.preventDefault();
        zone.classList.remove('dragover');
    }));
    zone.addEventListener('drop', e => {
        input.files = e.dataTransfer.files;
        zone.querySelector('strong').textContent = `${input.files.length} file(s) ready to upload`;
    });
})();
