Behaviour.specify("A.post-hyperlink-note-button", "POSTHyperLinkNote", 0, function (element) {
    element.addEventListener("click", (event) => {
        event.preventDefault();
        const url = decodeURIComponent(atob(element.dataset.encodedUrl));
        fetch(url, {
            method: "post",
            headers: crumb.wrap({}),
        });
    });
});
