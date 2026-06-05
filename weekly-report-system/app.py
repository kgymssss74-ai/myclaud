"""Flask application entry point (stub — Phase 2 will wire routes)."""
from flask import Flask

app = Flask(__name__)
app.config['UPLOAD_FOLDER'] = 'uploads'
app.config['DB_PATH'] = 'weekly.db'

# Blueprints registered in Phase 2:
# from routes.register import bp as register_bp
# from routes.weekly_input import bp as weekly_bp
# from routes.preview_diff import bp as diff_bp
# from routes.query import bp as query_bp
# app.register_blueprint(register_bp)
# app.register_blueprint(weekly_bp)
# app.register_blueprint(diff_bp)
# app.register_blueprint(query_bp)

if __name__ == '__main__':
    app.run(debug=True)
